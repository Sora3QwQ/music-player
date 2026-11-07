'use strict';
const el = id => document.getElementById(id);
let tracks = [], pending = null, timer = null, online = false, polling = false;
let catalogVersion = null, shownQueue = '', shownPlaylists = '';
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
    shownPlaylists = '';
    el('queue').replaceChildren();
    el('playlist').replaceChildren();
    el('settings').open = false;
    pending = null;
    el('track').textContent = '等待点歌';
    el('code').value = '';
    el('code').hidden = true;
  }
}
function renderPlaylist(state) {
  const playlists = state.playlists || [];
  const key = JSON.stringify(playlists);
  const select = el('playlist');
  if (key !== shownPlaylists) {
    shownPlaylists = key;
    select.replaceChildren(...playlists.map(list => new Option(list.name, list.id)));
  }
  select.value = state.playlist || '';
  select.disabled = !online || playlists.length === 0;
  const byId = new Map(tracks.map(track => [track.id, track.title]));
  const ids = playlists.length ? state.queue || [] : [];
  const queue = ids.map(id => ({id, title: byId.get(id), current: id === state.id}));
  const queueKey = JSON.stringify([online, queue]);
  el('track-count').textContent = ids.length + ' 首';
  el('queue-empty').hidden = ids.length > 0;
  el('queue-empty').textContent = !online ? '等待安卓播放机上线。' : playlists.length ?
    '在安卓播放器上为当前歌单添加曲目。' : '请更新安卓播放器以同步歌单。';
  if (queueKey !== shownQueue) {
    shownQueue = queueKey;
    el('queue').replaceChildren(...queue.map(track => {
      const row = document.createElement('li');
      const button = document.createElement('button');
      button.textContent = track.title || '曲目暂不可用';
      button.disabled = !online || !track.title;
      if (track.current) button.setAttribute('aria-current', 'true');
      button.onclick = () => command('track', track.id);
      row.append(button);
      return row;
    }));
  }
  const modes = {OFF:'顺序播放', ALL:'列表循环', ONE:'单曲循环'};
  el('repeat').textContent = modes[state.repeat] || '循环模式';
  el('repeat').dataset.active = String(state.repeat === 'ALL' || state.repeat === 'ONE');
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
    }
    renderPlaylist(state);
    el('online').textContent = online ? (state.playing ? '● 播放中' : '● 待命中') : '○ 离线';
    el('online').dataset.online = String(online);
    el('track').textContent = tracks.find(t => t.id === state.id)?.title || '等待点歌';
    for (const button of document.querySelectorAll('[data-action]')) button.disabled = !online || !state.playlist;
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
el('playlist').onchange = event => command('selectPlaylist', event.target.value);
el('seek').onchange = event => command('seek','',Number(event.target.value));
el('volume').onchange = event => command('volume','',Number(event.target.value));
for (const button of document.querySelectorAll('[data-action]')) button.onclick = () => command(button.dataset.action);
api('status').then(() => {view(true); poll();}).catch(() => view(false));
