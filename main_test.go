package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestRemoteLifecycle(t *testing.T) {
	s := &server{origin: "https://music.example.com", password: digest("a-long-test-password"), path: filepath.Join(t.TempDir(), "store.json"), sessions: make(map[string]time.Time)}
	ts := httptest.NewTLSServer(s)
	defer ts.Close()
	client := ts.Client()
	client.Jar, _ = cookiejar.New(nil)
	call := func(method, path, body, origin string, want int) []byte {
		t.Helper()
		req, err := http.NewRequest(method, ts.URL+path, bytes.NewBufferString(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Origin", origin)
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, err := io.ReadAll(res.Body)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != want {
			t.Fatalf("%s %s: got %d, want %d: %s", method, path, res.StatusCode, want, b)
		}
		return b
	}
	call("GET", "/api/tracks", "", "", 401)
	call("POST", "/api/command", `{"action":"play"}`, s.origin, 401)
	call("POST", "/api/login", `{"password":"a-long-test-password"}`, "https://other.example", 403)
	call("POST", "/api/login", `{"password":"a-long-test-password"}`, s.origin, 200)
	call("POST", "/api/command", `{"action":"play"}`, s.origin, 409)
	call("POST", "/api/revoke", "{}", "https://other.example", 403)
	var pair map[string]string
	if err := json.Unmarshal(call("POST", "/api/pair", "{}", s.origin, 200), &pair); err != nil {
		t.Fatal(err)
	}
	body := `{"code":"` + pair["code"] + `"}`
	var device map[string]string
	if err := json.Unmarshal(call("POST", "/device/pair", body, "", 200), &device); err != nil {
		t.Fatal(err)
	}
	call("POST", "/device/pair", body, "", 401)

	dialer := websocket.Dialer{TLSClientConfig: client.Transport.(*http.Transport).TLSClientConfig, HandshakeTimeout: time.Second}
	address := "wss" + strings.TrimPrefix(ts.URL, "https") + "/device/socket"
	header := http.Header{"Authorization": {"Bearer " + device["token"]}}
	ws, _, err := dialer.Dial(address, header)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.Close()
	id := strings.Repeat("a", 64)
	if err := ws.WriteJSON(map[string]any{"type": "catalog", "tracks": []Track{{ID: id, Title: "<img onerror=alert(1)>"}}}); err != nil {
		t.Fatal(err)
	}
	wait := func(path, value string) {
		t.Helper()
		for until := time.Now().Add(2 * time.Second); time.Now().Before(until); time.Sleep(10 * time.Millisecond) {
			if strings.Contains(string(call("GET", path, "", "", 200)), value) {
				return
			}
		}
		t.Fatalf("%s did not return %q", path, value)
	}
	wait("/api/tracks", id)
	call("POST", "/api/command", `{"action":"track","id":"file:///etc/passwd"}`, s.origin, 400)
	call("POST", "/api/command", `{"action":"volume","value":101}`, s.origin, 400)
	var queued map[string]string
	if err := json.Unmarshal(call("POST", "/api/command", `{"action":"track","id":"`+id+`"}`, s.origin, 200), &queued); err != nil {
		t.Fatal(err)
	}
	_ = ws.SetReadDeadline(time.Now().Add(time.Second))
	var received Command
	if err := ws.ReadJSON(&received); err != nil {
		t.Fatal(err)
	}
	if received.Request != queued["request"] || received.ID != id || received.Expires <= time.Now().UnixMilli() {
		t.Fatalf("invalid forwarded command: %+v", received)
	}
	if err := ws.WriteJSON(map[string]any{"type": "state", "state": State{ID: id, Playing: true, Ack: received.Request}}); err != nil {
		t.Fatal(err)
	}
	wait("/api/status", received.Request)
	if err := json.Unmarshal(call("POST", "/api/command", `{"action":"enqueue","id":"`+id+`"}`, s.origin, 200), &queued); err != nil {
		t.Fatal(err)
	}
	if err := ws.ReadJSON(&received); err != nil {
		t.Fatal(err)
	}
	if received.Action != "enqueue" || received.Request != queued["request"] {
		t.Fatalf("invalid queue command: %+v", received)
	}
	if err := ws.WriteJSON(map[string]any{"type": "state", "state": State{Queue: []string{id}, Ack: received.Request}}); err != nil {
		t.Fatal(err)
	}
	wait("/api/status", `"queue":["`+id+`"]`)
	call("POST", "/api/revoke", "{}", s.origin, 200)
	wait("/api/status", `"online":false`)
	if _, res, err := dialer.Dial(address, header); err == nil || res == nil || res.StatusCode != 401 {
		t.Fatal("revoked token accepted")
	}
	call("POST", "/api/logout", "{}", s.origin, 200)
	call("GET", "/api/status", "", "", 401)
}
