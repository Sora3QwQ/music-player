package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed web/*
var assets embed.FS

type Track struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Artist string `json:"artist"`
}
type State struct {
	Queue    []string `json:"queue"`
	ID       string   `json:"id"`
	Playing  bool     `json:"playing"`
	Position int64    `json:"position"`
	Duration int64    `json:"duration"`
	Volume   int      `json:"volume"`
	Error    string   `json:"error,omitempty"`
	Ack      string   `json:"ack,omitempty"`
}
type saved struct {
	Token  string
	Tracks []Track
}
type Command struct {
	Action  string `json:"action"`
	ID      string `json:"id"`
	Value   int64  `json:"value"`
	Expires int64  `json:"expires"`
	Request string `json:"request"`
}
type server struct {
	mu                     sync.Mutex
	origin, password, path string
	sessions               map[string]time.Time
	nextLogin              time.Time
	pair                   string
	pairUntil              time.Time
	disk                   saved
	catalogVersion         uint64
	device                 *websocket.Conn
	state                  State
}

func token() string {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}
func digest(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func equal(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }

func (s *server) persist(data saved) error {
	b, err := json.Marshal(data)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(s.path), "music-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	_, err = f.Write(b)
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(f.Name(), s.path); err != nil {
		return err
	}
	s.disk = data
	s.catalogVersion++
	return nil
}

func reply(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}
func read(w http.ResponseWriter, r *http.Request, v any) bool {
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
	d.DisallowUnknownFields()
	if d.Decode(v) != nil || d.Decode(new(any)) != io.EOF {
		http.Error(w, "invalid body", 400)
		return false
	}
	return true
}

func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
	if r.Method == "GET" {
		switch r.URL.Path {
		case "/", "/app.js", "/style.css":
			name := r.URL.Path
			if name == "/" {
				name = "/index.html"
			}
			b, _ := assets.ReadFile("web" + name)
			kind := map[string]string{"/index.html": "text/html", "/app.js": "text/javascript", "/style.css": "text/css"}[name]
			w.Header().Set("Content-Type", kind+"; charset=utf-8")
			_, _ = w.Write(b)
			return
		case "/device/socket":
			s.socket(w, r)
			return
		}
	}
	if r.Method == "POST" && r.URL.Path != "/device/pair" && r.Header.Get("Origin") != s.origin {
		http.Error(w, "forbidden origin", 403)
		return
	}
	if r.Method == "POST" {
		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 4096))
		if err != nil {
			http.Error(w, "invalid body", 400)
			return
		}
		r.Body = io.NopCloser(bytes.NewReader(body))
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	switch r.Method + " " + r.URL.Path {
	case "POST /device/pair":
		var req struct{ Code string }
		if !read(w, r, &req) {
			return
		}
		if !now.Before(s.pairUntil) || !equal(s.pair, req.Code) {
			http.Error(w, "invalid pairing code", 401)
			return
		}
		key := token()
		if err := s.persist(saved{Token: digest(key)}); err != nil {
			http.Error(w, "storage error", 500)
			return
		}
		s.pair = ""
		s.pairUntil = time.Time{}
		s.disconnect()
		reply(w, map[string]string{"token": key})
		return
	case "POST /api/login":
		var req struct{ Password string }
		if !read(w, r, &req) {
			return
		}
		if now.Before(s.nextLogin) {
			http.Error(w, "retry shortly", 429)
			return
		}
		s.nextLogin = now.Add(time.Second)
		if !equal(s.password, digest(req.Password)) {
			http.Error(w, "invalid credentials", 401)
			return
		}
		for key, until := range s.sessions {
			if !now.Before(until) {
				delete(s.sessions, key)
			}
		}
		if len(s.sessions) >= 64 {
			for key := range s.sessions {
				delete(s.sessions, key)
				break
			}
		}
		key := token()
		s.sessions[key] = now.Add(24 * time.Hour)
		s.cookie(w, key, 86400)
		reply(w, true)
		return
	}
	c, err := r.Cookie("__Host-music")
	if err != nil || !now.Before(s.sessions[c.Value]) {
		http.Error(w, "login required", 401)
		return
	}
	switch r.Method + " " + r.URL.Path {
	case "POST /api/logout":
		delete(s.sessions, c.Value)
		s.cookie(w, "", -1)
		reply(w, true)
	case "POST /api/pair":
		s.pair = token()
		s.pairUntil = now.Add(5 * time.Minute)
		reply(w, map[string]string{"code": s.pair})
	case "POST /api/revoke":
		if err := s.persist(saved{}); err != nil {
			http.Error(w, "storage error", 500)
			return
		}
		s.pair = ""
		s.pairUntil = time.Time{}
		s.disconnect()
		reply(w, true)
	case "GET /api/status":
		reply(w, map[string]any{"online": s.device != nil, "state": s.state, "catalogVersion": s.catalogVersion})
	case "GET /api/tracks":
		reply(w, s.disk.Tracks)
	case "POST /api/command":
		var cmd Command
		if !read(w, r, &cmd) {
			return
		}
		switch cmd.Action {
		case "track", "enqueue":
			found := false
			for _, track := range s.disk.Tracks {
				if track.ID == cmd.ID {
					found = true
					break
				}
			}
			if !found {
				http.Error(w, "unknown track", 400)
				return
			}
		case "play", "pause", "next", "previous", "clearQueue":
		case "volume":
			if cmd.Value < 0 || cmd.Value > 100 {
				http.Error(w, "invalid volume", 400)
				return
			}
		case "seek":
			if cmd.Value < 0 || cmd.Value > 86400000 {
				http.Error(w, "invalid position", 400)
				return
			}
		default:
			http.Error(w, "unknown command", 400)
			return
		}
		if s.device == nil {
			http.Error(w, "device offline", 409)
			return
		}
		cmd.Request = token()
		cmd.Expires = now.Add(10 * time.Second).UnixMilli()
		_ = s.device.SetWriteDeadline(now.Add(2 * time.Second))
		if err := s.device.WriteJSON(cmd); err != nil {
			s.disconnect()
			http.Error(w, "device offline", 409)
			return
		}
		reply(w, map[string]string{"request": cmd.Request})
	default:
		http.NotFound(w, r)
	}
}
func (s *server) cookie(w http.ResponseWriter, value string, age int) {
	http.SetCookie(w, &http.Cookie{Name: "__Host-music", Value: value, Path: "/", MaxAge: age, Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode})
}
func (s *server) disconnect() {
	if s.device != nil {
		_ = s.device.Close()
		s.device = nil
	}
	s.state = State{}
}

func (s *server) socket(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") || !equal(digest(strings.TrimPrefix(auth, "Bearer ")), s.disk.Token) {
		s.mu.Unlock()
		http.Error(w, "invalid device credentials", 401)
		return
	}
	upgrader := websocket.Upgrader{HandshakeTimeout: 3 * time.Second, CheckOrigin: func(r *http.Request) bool { return r.Header.Get("Origin") == "" }}
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.mu.Unlock()
		return
	}
	s.disconnect()
	s.device = ws
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.device == ws {
			s.disconnect()
		}
	}()
	ws.SetReadLimit(8 << 20)
	ws.SetPingHandler(func(payload string) error {
		if err := ws.SetReadDeadline(time.Now().Add(80 * time.Second)); err != nil {
			return err
		}
		return ws.WriteControl(websocket.PongMessage, []byte(payload), time.Now().Add(2*time.Second))
	})
	for {
		_ = ws.SetReadDeadline(time.Now().Add(80 * time.Second))
		var event struct {
			Type   string
			Tracks []Track
			State  State
		}
		if ws.ReadJSON(&event) != nil {
			return
		}
		s.mu.Lock()
		if s.device != ws {
			s.mu.Unlock()
			return
		}
		switch event.Type {
		case "heartbeat":
		case "state":
			if len(event.State.Queue) > 100 || len(event.State.Error) > 1024 {
				s.mu.Unlock()
				return
			}
			s.state = event.State
		case "catalog":
			if len(event.Tracks) > 10000 {
				s.mu.Unlock()
				return
			}
			seen := make(map[string]bool, len(event.Tracks))
			for _, track := range event.Tracks {
				if len(track.ID) != 64 || len(track.Title) > 1024 || len(track.Artist) > 1024 || seen[track.ID] {
					s.mu.Unlock()
					return
				}
				seen[track.ID] = true
			}
			if err := s.persist(saved{s.disk.Token, event.Tracks}); err != nil {
				log.Print("catalog storage failed")
				s.mu.Unlock()
				return
			}
		default:
			s.mu.Unlock()
			return
		}
		s.mu.Unlock()
	}
}

func main() {
	origin := os.Getenv("PUBLIC_ORIGIN")
	u, err := url.Parse(origin)
	pw := os.Getenv("ADMIN_PASSWORD")
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || origin != "https://"+u.Host || len(pw) < 16 {
		log.Fatal("PUBLIC_ORIGIN must be an HTTPS origin and ADMIN_PASSWORD must have at least 16 bytes")
	}
	path := os.Getenv("DATA_DIR")
	if path == "" {
		path = "data"
	}
	if err := os.MkdirAll(path, 0700); err != nil {
		log.Fatal(err)
	}
	s := &server{origin: origin, password: digest(pw), path: filepath.Join(path, "store.json"), sessions: make(map[string]time.Time)}
	if b, err := os.ReadFile(s.path); err == nil {
		if err = json.Unmarshal(b, &s.disk); err != nil {
			log.Fatal(err)
		}
	} else if !os.IsNotExist(err) {
		log.Fatal(err)
	}
	h := &http.Server{Addr: ":8080", Handler: s, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 8 * time.Second, WriteTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Fatal(h.ListenAndServe())
}
