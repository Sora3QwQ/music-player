'use strict';
const el = id => document.getElementById(id);
let tracks = [], pending = null, timer = null, online = false, polling = false;
let catalogVersion = null, shownQueue = '';
const notice = message => { el('notice').textContent = message; };
async function api(path, body) {
  const options = body === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  };
  const response = await fetch('/api/' + path, options);
  if (!response.ok) {
    const messages = {400:'指令无效',401:'请检查密码或重新登录',403:'请求来源无效',409:'设备离线',429:'请稍后再试'};
    throw Object.assign(new Error(messages[response.status] || '服务暂不可用'), {status:response.status});
  }
  return response.json();
}
function view(loggedIn) {
  el('login').hidden = loggedIn;
  el('player').hidden = !loggedIn;
  if (timer) clearInterval(timer);
  timer = loggedIn ? setInterval(poll, 1500) : null;
  if (!loggedIn) {
    tracks = [];
    catalogVersion = null;
    shownQueue = '';
    el('queue').replaceChildren();
    pending = null;
    renderTracks();
    el('track').textContent = '等待点歌';
    el('code').value = '';
    el('code').hidden = true;
  }
}
function renderTracks() {
  const query = el('search').value.toLowerCase().trim();
  const list = el('tracks');
  list.replaceChildren();
  const results = tracks.filter(t => (t.title + ' ' + t.artist).toLowerCase().includes(query));
  el('track-count').textContent = results.length > 200 ? '显示 200 / ' + results.length + ' 首' : results.length + ' 首';
  el('library-empty').hidden = results.length > 0;
  el('library-empty').textContent = query ? '没有匹配的歌曲' : '等待安卓播放机同步曲库';
  for (const track of results.slice(0, 200)) {
    const row = document.createElement('li');
    const button = document.createElement('button');
    button.textContent = track.artist ? track.title + ' · ' + track.artist : track.title;
    button.disabled = !online;
    button.onclick = () => command('track', track.id);
    const enqueue = document.createElement('button');
    enqueue.textContent = '加入队列';
    enqueue.setAttribute('aria-label', '将 ' + track.title + ' 加入队列');
    enqueue.disabled = !online;
    enqueue.onclick = () => command('enqueue', track.id);
    row.className = 'track-row';
    row.append(button, enqueue);
    list.append(row);
  }
}
async function poll() {
  if (polling || el('player').hidden) return;
  polling = true;
  try {
    const {online: connected, state, catalogVersion: version} = await api('status');
    const changed = version !== catalogVersion;
    const library = changed ? await api('tracks') || [] : tracks;
    if (el('player').hidden) return;
    if (connected !== online || changed) {
      online = connected;
      tracks = library;
      catalogVersion = version;
      renderTracks();
    }
    const queue = (state.queue || []).map(id => tracks.find(t => t.id === id)?.title || '未知曲目');
    el('queue-empty').hidden = queue.length > 0;
    const queueKey = JSON.stringify(queue);
    if (queueKey !== shownQueue) {
      shownQueue = queueKey;
      el('queue').replaceChildren(...queue.map(title => {
        const row = document.createElement('li');
        row.textContent = title;
        return row;
      }));
    }
    el('online').textContent = online ? (state.playing ? '● 播放中' : '● 待命中') : '○ 离线';
    el('online').dataset.online = String(online);
    el('track').textContent = tracks.find(t => t.id === state.id)?.title || '等待点歌';
    for (const button of document.querySelectorAll('[data-action]')) button.disabled = !online;
    for (const id of ['seek','volume']) el(id).disabled = !online;
    if (document.activeElement !== el('seek')) {
      el('seek').max = state.duration;
      el('seek').value = state.position;
    }
    if (document.activeElement !== el('volume')) el('volume').value = state.volume;
    const format = ms => Math.floor(ms / 60000) + ':' + String(Math.floor(ms / 1000) % 60).padStart(2,'0');
    el('time').textContent = format(state.position) + ' / ' + format(state.duration);
    if (pending && state.ack === pending.id) {
      notice(state.error || '设备已确认');
      pending = null;
    } else if (pending && (!online || Date.now() > pending.until)) {
      notice('设备未确认指令');
      pending = null;
    }
  } catch (error) {
    if (error.status === 401) view(false);
    notice(error.message);
  } finally { polling = false; }
}
async function command(action, id = '', value = 0) {
  try {
    const result = await api('command', {action,id,value});
    pending = {id:result.request, until:Date.now()+8000};
    notice('等待设备确认…');
    poll();
  } catch (error) { notice(error.message); }
}
el('login').onsubmit = async event => {
  event.preventDefault();
  try {
    await api('login', {password:el('password').value});
    notice('');
    view(true);
    poll();
  } catch (error) { notice(error.message); }
  finally { el('password').value = ''; }
};
el('logout').onclick = async () => {
  try { await api('logout', {}); view(false); notice('已退出'); }
  catch (error) { notice(error.message); }
};
el('pair').onclick = async () => {
  try {
    el('code').value = (await api('pair', {})).code;
    el('code').hidden = false;
    notice('将配对码粘贴到安卓端，五分钟内有效。');
  } catch (error) { notice(error.message); }
};
el('revoke').onclick = async () => {
  if (!confirm('解绑后需要在安卓端重新配对。继续？')) return;
  try { await api('revoke', {}); el('code').hidden = true; el('code').value = ''; notice('已解绑'); poll(); }
  catch (error) { notice(error.message); }
};
el('search').oninput = renderTracks;
el('seek').onchange = event => command('seek','',Number(event.target.value));
el('volume').onchange = event => command('volume','',Number(event.target.value));
for (const button of document.querySelectorAll('[data-action]')) button.onclick = () => command(button.dataset.action);
api('status').then(() => {view(true); poll();}).catch(() => view(false));
