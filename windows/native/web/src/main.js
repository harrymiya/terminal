import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import THEMES from './themes.json';

const $ = (id) => document.getElementById(id);
const hostList = $('host-list');
const agentList = $('agent-list');
const workspaceList = $('workspace-list');
const stage = $('session-stage');
const terminalGrid = $('terminal-grid');
const emptyState = $('empty-state');
const formDialog = $('form-dialog');
const form = formDialog.querySelector('form');
let config = { hosts: [], agents: [], tiled: true, ui_scale: 100 };
let activeHost = '';
let activeSession = '';
let viewMode = 'tabs';
let tileLayout = 'grid';
let editing = false;
let lastConfigSnapshot = '';
let browseTargetInput = null;
const pendingMcp = new Map();
const sessions = new Map();
const metricsByHost = new Map();
const quotaByAgent = new Map();
const sshEndpoints = new Map();
const encoder = new TextEncoder();

function post(message) {
  if (window.chrome?.webview) window.chrome.webview.postMessage(message);
  else showError('请从 Remote Dev Terminal Windows 程序启动此界面。');
}
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' })[c]);
}
function currentHost() { return config.hosts.find((h) => h.id === activeHost) || null; }
function agentsForHost(host) { return host?.agents?.length ? host.agents : config.agents; }
function currentAgent() { const list=agentsForHost(currentHost()); return list[Math.max(0, Number(currentHost()?.selected_agent)||0)] || null; }
function sshEndpointSignature(host) {
  const lan=String(host.lan_address||'');
  let external=String(host.external_address||'');
  if(!lan&&!external)external=String(host.address||'');
  const parsePort=(value,fallback)=>{const port=Number(value);return Number.isInteger(port)&&port>=1&&port<=65535?port:fallback;};
  const lanPort=parsePort(host.lan_port,parsePort(host.port,22));
  const externalPort=parsePort(host.external_port,parsePort(host.port,22));
  return [String(host.id||''),String(host.username||''),lan,String(lanPort),external,String(externalPort)].join('\u001f');
}
function quotaKey(hostId,command) { return `${hostId||''}\0${command||''}`; }
function supportsQuota(command) { const executable=String(command||'').trim().replace(/^["']|["']$/g,'').split(/\s+/,1)[0].split(/[\\/]/).pop().toLowerCase(); return ['codex','codex2','codx','agy','agy2'].includes(executable); }
function makeId() { return crypto.randomUUID().replaceAll('-', ''); }
function currentTheme() { return THEMES[config.theme] || THEMES.cyberpunk; }
function applyTheme() {
  const t=currentTheme(), root=document.documentElement;
  const vars={bg:t.win_bg,panel:t.surface,panel2:t.control_bg,panel3:t.control_hover,line:t.border,text:t.fg,muted:t.subtitle,accent:t.accent,'accent-dark':t.accent_bg,danger:t.quota_low,'sidebar-bg':t.sidebar_bg,'toolbar-bg':t.toolbar_bg,'popup-bg':t.popup_bg,'control-fg':t.control_fg,brand:t.brand,section:t.section,'manage-fg':t.manage_fg,'tab-fg':t.tab_fg,'tile-grid-bg':t.tile_grid_bg,'tile-header-bg':t.tile_header_bg,'tile-focused-bg':t.tile_focused_bg,'drop-border':t.drop_border,'drop-bg':t.drop_bg,'drop-header':t.drop_header,'hover-overlay':t.hover_overlay,'quota-ok':t.quota_ok,'quota-warn':t.quota_warn,'dot-idle':t.dot_idle,'dot-run':t.dot_run,'dot-wait':t.dot_wait,'term-bg':t.term_bg,'term-fg':t.term_fg,'accent-text':t.accent_text,'accent-fg':t.accent_fg};
  for(const [key,value] of Object.entries(vars)) root.style.setProperty(`--${key}`,value);
  root.style.colorScheme=config.theme==='light'?'light':'dark';
  $('theme-select').value=Object.keys(THEMES).includes(config.theme)?config.theme:'cyberpunk';
  for(const s of sessions.values()) s.term.options.theme=terminalTheme(t);
}
function terminalTheme(t=currentTheme()) {
  const palette=t.term_palette||['#182230','#b91c1c','#15803d','#a16207','#1d4ed8','#7e22ce','#0e7490','#475569','#64748b','#dc2626','#16a34a','#ca8a04','#2563eb','#9333ea','#0284c7','#0f172a'];
  return {background:t.term_bg,foreground:t.term_fg,cursor:t.cursor_color||t.accent,selectionBackground:t.highlight_bg||t.accent_bg,selectionForeground:t.highlight_fg||t.accent_fg,black:palette[0],red:palette[1],green:palette[2],yellow:palette[3],blue:palette[4],magenta:palette[5],cyan:palette[6],white:palette[7],brightBlack:palette[8],brightRed:palette[9],brightGreen:palette[10],brightYellow:palette[11],brightBlue:palette[12],brightMagenta:palette[13],brightCyan:palette[14],brightWhite:palette[15]};
}
function terminalFontSize(scale=Number($('scale').value)) {
  const factor=scale>=100?scale/100:Math.max(.88,1-(1-scale/100)*.24);
  return 13*factor;
}
function sidebarMinimumWidth(scale=Number($('scale').value)) {
  const target=scale>=100?Math.round(250*scale/100):Math.max(210,Math.round(250*scale/100));
  return target+32;
}
function applyUiScale(scale=Number($('scale').value)) {
  const value=Math.max(50,Math.min(200,Number(scale)||100));
  $('scale').value=String(value);
  document.documentElement.style.setProperty('--ui-scale',String(value/100));
  const minimum=sidebarMinimumWidth(value);
  config.sidebar_width=Math.max(minimum,Number(config.sidebar_width)||294);
  document.querySelector('.sidebar').style.width=`${config.sidebar_width}px`;
  for(const session of sessions.values()) {
    session.term.options.fontSize=terminalFontSize(value);
    try{session.fit.fit();}catch{}
  }
}
function fitSessions(){for(const session of sessions.values())try{session.fit.fit();}catch{}}
function sendConfig() {
  const previous=lastConfigSnapshot?JSON.parse(lastConfigSnapshot):{hosts:[],agents:[]};
  const oldHosts=new Map((previous.hosts||[]).map(h=>[h.id,h]));
  config.dirty_host_profiles ||= [];
  config.dirty_workspaces ||= [];
  for(const host of config.hosts){
    const old=oldHosts.get(host.id);
    if(!old||['name','username','lan_address','lan_port','external_address','external_port','sync_id','icon','selected_agent'].some(k=>JSON.stringify(old[k]??'')!==JSON.stringify(host[k]??'')))
      if(!config.dirty_host_profiles.includes(host.id))config.dirty_host_profiles.push(host.id);
    if(!old||JSON.stringify(old.directories||[])!==JSON.stringify(host.directories||[]))
      if(!config.dirty_workspaces.includes(host.id))config.dirty_workspaces.push(host.id);
  }
  for(const old of previous.hosts||[])if(!config.hosts.some(h=>h.id===old.id)){config.deleted_host_profiles ||= [];if(!config.deleted_host_profiles.includes(old.id))config.deleted_host_profiles.push(old.id);}
  if(JSON.stringify(previous.agents||[])!==JSON.stringify(config.agents||[]))config.agents_dirty=true;
  config.current_host_id = activeHost;
  config.tiled = viewMode === 'grid';
  config.ui_scale = Number($('scale').value);
  config.tile_layout = tileLayout;
  lastConfigSnapshot=JSON.stringify(config);
  post({ action: 'save-config', config });
}
function setBusy(value) { $('connection-status').classList.toggle('busy', value); }
function showError(message) { window.alert(message); }
function autoEnterSignature(term) {
  const buffer=term.buffer.active;
  const start=Math.max(0,buffer.viewportY ?? buffer.baseY ?? 0);
  const lines=[];
  for(let row=start;row<start+term.rows;row++) {
    let line=buffer.getLine(row)?.translateToString(true)||'';
    line=line.trim().replace(/^[│┃║| ]+|[│┃║| ]+$/g,'').trim();
    if(line&&!/^\[(?:rdt_[0-9a-fA-F]+|\d+)\]\s+\d+:\S+/.test(line))lines.push(line);
  }
  const normalize=value=>value.replace(/\b\d{1,2}:\d{2}(?::\d{2})?\b/g,'').replace(/\(\d+(?:\.\d+)?s\)/g,'').replace(/[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]/g,'').trim();
  const option=/^\s*(?:[›❯>➜→*●◉]|\[[xX*]\]|\([xX*]\))\s*(?:\d+[.)]\s*)?(.*)$/;
  const cursor=/^\s*(?:[›❯●◉]|\[[xX*]\]|\([xX*]\))/;
  const affirmative=/^(?:yes\b|allow\b|approve\b|proceed\b|continue\b|always\b|允许|批准|同意|继续|确认|总是)/i;
  const request=/(?:approv(?:e|al)|allow|would you like|do you want|proceed|continue|permission|confirm|execute|apply|install|run|resume|recover|restore|授权|批准|是否允许|需要确认|是否继续|确认执行|[?？])/i;
  const enterPrompt=/(?:press\s+(?:enter|return)\s+to\s+(?:continue|confirm|approve)|按\s*(?:回车|enter)\s*(?:继续|确认|批准|同意))[:\s?.!]*$/i;
  const yesDefault=/[\[(](?:Y\/(?:n|no)|Yes\/no)[\])][:\s?.]*$/;
  for(let index=lines.length-1;index>Math.max(-1,lines.length-45);index--){
    const match=option.exec(lines[index]);if(!match)continue;
    if(!affirmative.test(match[1]))return '';
    for(let question=index-1;question>Math.max(-1,index-150);question--){
      if(request.test(lines[question]))return normalize(lines.slice(Math.max(0,question-4),index+1).join('\n'));
    }
    if(cursor.test(lines[index]))return normalize(lines.slice(Math.max(0,index-4),index+1).join('\n'));
    return '';
  }
  for(let index=lines.length-1;index>Math.max(-1,lines.length-20);index--)
    if(yesDefault.test(lines[index])||enterPrompt.test(lines[index]))return normalize(lines.slice(Math.max(0,index-4),index+1).join('\n'));

  const clean=lines.filter(line=>!Array.from(line).every(char=>'─━═╭╮╰╯┌┐└┘+ '.includes(char)));
  const footerPattern=/^press\s+(?:enter|return)\s+to\s+confirm\s+or\s+(?:esc|escape)\s+to\s+cancel[.!]?$/i;
  let footer=-1;
  for(let index=clean.length-1;index>Math.max(-1,clean.length-5);index--)
    if(footerPattern.test(clean.slice(index).join(' '))){footer=index;break;}
  if(footer<0)return '';
  const approvalCursor=/^(?:[›❯➜●◉]|>\s*\d+[.)])/;
  let selected=-1;
  for(let index=footer-1;index>=0;index--)if(approvalCursor.test(clean[index])){selected=index;break;}
  if(selected<0)return '';
  const choice=/^(?:[›❯>➜●◉]\s*)?\d+[.)]\s*(.*)$/.exec(clean[selected]);
  if(!choice)return '';
  const parts=[choice[1]];
  for(const line of clean.slice(selected+1,Math.min(footer,selected+6))){if(/^(?:[›❯>➜●◉]\s*)?\d+[.)]/.test(line))break;parts.push(line);}
  const label=parts.join(' ').replace(/\s+/g,' ').trim();
  if(parts.length===1||! /^(?:yes,\s*(?:proceed|just this once|grant these permissions for this turn|grant for this turn with strict auto review)|allow once)\s*(?:\([a-z]\))?$/i.test(label))return '';
  return normalize([...clean.slice(Math.max(0,selected-2),selected),label].join('\n'));
}
function watchAutoEnter(session) {
  if(!config.auto_enter||!session.connected||session.autoState.sent)return;
  const signature=autoEnterSignature(session.term);
  if(!signature){session.autoState.signature='';session.autoState.since=0;return;}
  if(signature!==session.autoState.signature){session.autoState.signature=signature;session.autoState.since=Date.now();return;}
  if(Date.now()-session.autoState.since>=Math.max(50,Number(config.auto_enter_delay)||1000)){session.autoState.sent=true;post({action:'input',id:session.id,data:'\r'});}
}
function updateEditing() {
  document.body.classList.toggle('editing', editing);
  $('edit-mode').classList.toggle('selected', editing);
  $('edit-mode').textContent = editing ? '完成' : '编辑';
  renderHosts();
  renderAgents();
  renderWorkspaces();
}
function normalizeConfig(raw) {
  config = raw && typeof raw === 'object' ? raw : { hosts: [], agents: [] };
  if (!Array.isArray(config.hosts)) config.hosts=[];
  if (!Array.isArray(config.agents)) config.agents=[];
  if (!config.agents.length) config.agents=[{name:'Codex',command:'codex'},{name:'OpenCode',command:'opencode'},{name:'Antigravity',command:'agy'},{name:'Shell',command:'bash -l'}];
  for (const [i,host] of config.hosts.entries()) {
    host.id ||= makeId();
    host.directories ||= [];
    if (!host.lan_address && !host.external_address && host.address) host.external_address=host.address;
    host.selected_agent=Number.isInteger(host.selected_agent) ? host.selected_agent : 0;
    host.port=Number(host.port || host.lan_port || host.external_port || 22);
    host.lan_port=Number(host.lan_port || host.port || 22);
    host.external_port=Number(host.external_port || host.port || 22);
  }
  activeHost = config.current_host_id || config.hosts[0]?.id || '';
  viewMode = config.tiled ? 'grid' : 'tabs';
  tileLayout=['grid','columns','rows'].includes(config.tile_layout)?config.tile_layout:'grid';
  $('scale').value = Math.min(200, Math.max(50, Number(config.ui_scale)||100));
  config.sidebar_width=Math.min(2000,Math.max(sidebarMinimumWidth(Number($('scale').value)),Number(config.sidebar_width)||294));
  document.querySelector('.sidebar').style.width=`${config.sidebar_width}px`;
  document.body.classList.toggle('sidebar-hidden',Boolean(config.sidebar_hidden));
  $('auto-enter').checked = Boolean(config.auto_enter);
  $('auto-enter-delay').value=String(Math.min(30000,Math.max(50,Number(config.auto_enter_delay)||1000)));
  $('quota-interval').value=String(Math.min(300,Math.max(15,Number(config.agent_quota_interval_seconds)||60)));
  $('metrics-interval').value=String(Math.min(300,Math.max(5,Number(config.host_metrics_interval_seconds)||5)));
  $('sync-interval').value=String(Math.min(300,Math.max(5,Number(config.sync_interval_seconds)||5)));
  $('sync-url').value=config.sync_url||'';
  $('auto-sync').checked=Boolean(config.auto_sync_enabled);
  $('offline-mode').checked=Boolean(config.offline_mode);
  $('sync-account').textContent=config.sync_username||'未登录';
  $('sync-admin-users').hidden=!config.sync_is_admin;
  $('shared-sessions').disabled=!config.sync_username;
  applyTheme();
  applyUiScale(Number($('scale').value));
  lastConfigSnapshot=JSON.stringify(config);
  render();
}
function renderHosts() {
  hostList.replaceChildren();
  config.hosts.forEach((host,index)=>{
    const row=document.createElement('div');row.className='flat-list-row';
    const button=document.createElement('button');button.type='button';button.className=`flat-list-button${host.id===activeHost?' selected':''}`;
    const name=document.createElement('span');name.className='flat-list-name';name.textContent=host.name||host.username||'主机';
    const remembered=sshEndpoints.get(host.id);
    const endpoint=remembered?.signature===sshEndpointSignature(host)?remembered:null;
    const probing=!endpoint&&host.lan_address&&host.external_address;
    const address=endpoint?.address||host.lan_address||host.external_address||host.address||'';
    const port=endpoint?.port||(host.lan_address?host.lan_port||host.port||22:host.external_port||host.port||22);
    const detail=document.createElement('span');detail.className='flat-list-detail';detail.textContent=probing?`${host.username||''}@正在探测 SSH 地址…`:`${host.username||''}@${address}:${port}`;
    button.append(name,detail);
    const metrics=metricsByHost.get(host.id);
    if(metrics){const chips=document.createElement('span');chips.className='metric-pair';for(const [label,value] of [['MEM',metrics.memory],['CPU',metrics.cpu]]){const chip=document.createElement('span');chip.className='metric-chip';chip.textContent=`${label} ${value.toFixed(0)}%`;chips.append(chip);}button.append(chips);}
    button.title=`${detail.textContent}\n点击切换主机`;
    button.addEventListener('click',()=>{activeHost=host.id;sendConfig();render();});row.append(button);
    if(editing){const actions=document.createElement('span');actions.className='flat-list-actions';const edit=document.createElement('button');edit.type='button';edit.textContent='✎';edit.title='编辑主机';edit.addEventListener('click',()=>editHost(host));const remove=document.createElement('button');remove.type='button';remove.textContent='×';remove.title='删除主机';remove.addEventListener('click',()=>{if(!confirm(`删除主机“${host.name}”及其工作区？`))return;if(host.credential_id)post({action:'clear-host-password',credential_id:host.credential_id});post({action:'delete-host-profile',host_id:host.id});config.hosts.splice(index,1);activeHost=config.hosts[0]?.id||'';sendConfig();render();});actions.append(edit,remove);row.append(actions);}
    hostList.append(row);
  });
  if(!config.hosts.length){const hint=document.createElement('div');hint.className='muted small empty-note';hint.textContent='请点击顶部“编辑”后添加主机';hostList.append(hint);}
  $('add-host').disabled=false;
  renderSyncStatus();
}
function renderAgents() {
  const host=currentHost(), list=agentsForHost(host);
  agentList.replaceChildren();
  list.forEach((agent,index)=>{
    const row=document.createElement('div');row.className='flat-list-row';
    const button=document.createElement('button');button.type='button';button.className=`flat-list-button${index===(host?.selected_agent||0)?' selected':''}`;
    const name=document.createElement('span');name.className='flat-list-name';name.textContent=agent.name||agent.command;
    const quota=quotaByAgent.get(quotaKey(host?.id,agent.command));const windows=quota?.windows||[];const detail=document.createElement('span');detail.className='flat-list-detail';detail.textContent=windows.length?windows.map(item=>`${item.label} ${item.remaining}%${item.reset?` · ${item.reset}`:''}`).join(' · '):quota?.error?'额度暂不可用':supportsQuota(agent.command)?'正在获取额度…':agent.command;
    button.append(name,detail);button.title=`${agent.command}${quota?.error?`\n${quota.error}`:''}\n点击选择开发者`;button.addEventListener('click',()=>{if(!host)return;host.selected_agent=index;sendConfig();render();});row.append(button);
    if(editing){const actions=document.createElement('span');actions.className='flat-list-actions';const up=document.createElement('button');up.type='button';up.textContent='↑';up.title='上移开发者';up.disabled=index===0;up.addEventListener('click',()=>moveAgentAt(index,-1));const down=document.createElement('button');down.type='button';down.textContent='↓';down.title='下移开发者';down.disabled=index===list.length-1;down.addEventListener('click',()=>moveAgentAt(index,1));const edit=document.createElement('button');edit.type='button';edit.textContent='✎';edit.title='编辑开发者';edit.addEventListener('click',()=>editAgent(index));const remove=document.createElement('button');remove.type='button';remove.textContent='×';remove.title='删除开发者';remove.addEventListener('click',()=>{const target=host&&host.agents?.length?host.agents:config.agents;if(!confirm(`删除开发者“${agent.name}”？`))return;target.splice(index,1);if(host)host.selected_agent=Math.max(0,index-1);sendConfig();render();});actions.append(up,down,edit,remove);row.append(actions);}
    agentList.append(row);
  });
  if(!list.length){const hint=document.createElement('div');hint.className='muted small empty-note';hint.textContent='请先选择主机，再点击顶部“编辑”添加开发者';agentList.append(hint);}
  $('add-agent').disabled=false;
  renderAgentQuota();
}
function renderSyncStatus(){const root=$('sync-status');if(config.offline_mode)root.textContent='离线模式';else if(config.auto_sync_enabled&&config.sync_username)root.textContent=`跨设备同步 · ${config.sync_username}`;else if(config.sync_username)root.textContent=`已登录 · ${config.sync_username}`;else if(config.sync_url)root.textContent='同步账号未登录';else root.textContent='跨设备同步未配置';}
function renderAgentQuota(){const host=currentHost(),agent=currentAgent(),q=agent?quotaByAgent.get(quotaKey(host?.id,agent.command)):null,windows=q?.windows||[];const root=$('agent-quota');root.textContent='';root.title=q?.error||'';if(windows.length){root.textContent=windows.map(item=>`${item.label} ${item.remaining}%${item.reset?` · ${item.reset}`:''}`).join('   ');return;}if(agent&&supportsQuota(agent.command))root.textContent=q?.error?'额度暂不可用':'正在获取开发者额度…';}
function renderWorkspaces() {
  const host=currentHost(), workspaces=host?.directories || [];
  workspaceList.replaceChildren();
  $('empty-workspaces').style.display=workspaces.length?'none':'block';
  workspaces.forEach((workspace,index)=>{
    const row=document.createElement('div'); row.className='workspace-row';
    const open=document.createElement('button'); open.className='workspace-button';
    open.innerHTML=`<span class="workspace-icon">▣</span><span class="workspace-name">${escapeHtml(workspace.name || workspace.path)}</span>`;
    open.title=workspace.path || ''; open.addEventListener('click',()=>openSession(index)); row.append(open);
    const actions=document.createElement('span'); actions.className='workspace-edit';
    const up=document.createElement('button'); up.textContent='↑';up.title='上移工作区';up.disabled=index===0;up.addEventListener('click',()=>moveWorkspace(index,-1));
    const down=document.createElement('button'); down.textContent='↓';down.title='下移工作区';down.disabled=index===workspaces.length-1;down.addEventListener('click',()=>moveWorkspace(index,1));
    const edit=document.createElement('button'); edit.textContent='✎'; edit.title='编辑工作区'; edit.addEventListener('click',()=>editWorkspace(index));
    const remove=document.createElement('button'); remove.textContent='×'; remove.title='删除工作区'; remove.addEventListener('click',()=>deleteWorkspace(index));
    actions.append(up,down,edit,remove); row.append(actions); workspaceList.append(row);
  });
  $('add-workspace').disabled=!host;
}
function renderSessions() {
  const has=sessions.size>0;
  emptyState.classList.toggle('hidden',has);
  terminalGrid.classList.toggle('visible',has);
  $('view-mode').textContent=viewMode==='tabs'?'标签模式':'平铺模式';
  $('layout-grid').hidden=viewMode!=='grid';
  terminalGrid.className=`terminal-grid ${tileLayout}${has?' visible':''}`;
  terminalGrid.replaceChildren();
  for (const s of sessions.values()) {
    const card=document.createElement('section'); card.className=`terminal-card${viewMode==='tabs' && s.id!==activeSession?' inactive':''}`;
    card.innerHTML=`<div class="terminal-head"><span class="terminal-card-title" title="双击重命名">${escapeHtml(s.title)}</span><span class="terminal-tools"><button class="terminal-close rename-session" title="重命名">✎</button><button class="terminal-close image-session" title="发送图片">▧</button><button class="terminal-close minimize-session" title="最小化">−</button><button class="terminal-close float-session" title="浮动窗口">□</button><button class="terminal-close reconnect" title="重新连接">↻</button><button class="terminal-close close-session" title="关闭终端">×</button></span></div><div class="terminal-body"></div>`;
    card.querySelector('.terminal-close.close-session').addEventListener('click',()=>closeSession(s.id));
    card.querySelector('.terminal-close.reconnect').addEventListener('click',()=>connectSession(s));
    card.querySelector('.terminal-close.rename-session').addEventListener('click',()=>renameSession(s));
    card.querySelector('.terminal-card-title').addEventListener('dblclick',()=>renameSession(s));
    card.querySelector('.terminal-close.image-session').addEventListener('click',()=>chooseImage(s));
    card.querySelector('.terminal-close.minimize-session').addEventListener('click',()=>minimizeSession(s));
    card.querySelector('.terminal-close.float-session').addEventListener('click',()=>toggleFloat(s,card));
    card.draggable=true;
    card.addEventListener('dragstart',event=>{event.dataTransfer.setData('text/plain',s.id);event.dataTransfer.effectAllowed='move';card.classList.add('dragging');});
    card.addEventListener('dragend',()=>card.classList.remove('dragging'));
    card.addEventListener('dragover',event=>{event.preventDefault();card.classList.add('drop-target');});
    card.addEventListener('dragleave',()=>card.classList.remove('drop-target'));
    card.addEventListener('drop',event=>{event.preventDefault();card.classList.remove('drop-target');moveSession(event.dataTransfer.getData('text/plain'),s.id);});
    card.addEventListener('pointerdown',()=>{activeSession=s.id;});
    if(s.minimized) card.classList.add('terminal-minimized');
    if(s.floating) card.classList.add('terminal-floating');
    const body=card.querySelector('.terminal-body'); terminalGrid.append(card); s.card=card;
    if (!s.opened) { s.term.open(body); s.opened=true; }
    else if (s.term.element && s.term.element.parentElement!==body) body.append(s.term.element);
    s.term.element.style.height='100%';
    if(!s.minimized) s.fit.fit();
    if(s.id===activeSession)s.term.focus();
  }
  $('session-count').textContent=`${sessions.size} / 8`;
  $('layout-grid').disabled=viewMode!=='grid' || sessions.size<2;
  renderInstances();
  renderMinimized();
}
function render() { renderHosts(); renderAgents(); renderWorkspaces(); renderSessions(); updateEditing(); }

function renderInstances(){const section=$('instances-section'),root=$('instances-list');root.replaceChildren();const items=[...sessions.values()].filter(session=>!session.minimized&&!session.floating);section.hidden=viewMode==='grid'||!items.length;if(section.hidden)return;for(const session of items){const row=document.createElement('div');row.className='flat-list-row';const button=document.createElement('button');button.type='button';button.className=`flat-list-button${session.id===activeSession?' selected':''}`;const name=document.createElement('span');name.className='flat-list-name';name.textContent=session.title;const detail=document.createElement('span');detail.className='flat-list-detail';detail.textContent=`${session.workspace.name||session.workspace.path} · ${session.agent.name||session.agent.command}`;button.append(name,detail);button.addEventListener('click',()=>{activeSession=session.id;renderSessions();session.term.focus();});row.append(button);const actions=document.createElement('span');actions.className='flat-list-actions instance-actions';const rename=document.createElement('button');rename.type='button';rename.textContent='✎';rename.title='编辑实例名称';rename.addEventListener('click',()=>renameSession(session));const image=document.createElement('button');image.type='button';image.textContent='▧';image.title='发送图片';image.addEventListener('click',()=>chooseImage(session));const close=document.createElement('button');close.type='button';close.textContent='×';close.title='关闭终端';close.addEventListener('click',()=>closeSession(session.id));actions.append(rename,image,close);row.append(actions);root.append(row);}}

function renameSession(session){const name=prompt('终端会话名称',session.title);if(name?.trim()){session.title=name.trim();post({action:'session-sync',method:'PUT',host:session.host,item:{id:session.id,directory_path:session.workspace.path,directory_name:session.workspace.name,agent_name:session.agent.name,agent_command:session.agent.command,display_name:session.title}});renderSessions();}}
function moveSession(sourceId,targetId){if(!sourceId||sourceId===targetId)return;const items=[...sessions.entries()];const from=items.findIndex(([,s])=>s.id===sourceId),to=items.findIndex(([,s])=>s.id===targetId);if(from<0||to<0)return;const [item]=items.splice(from,1);items.splice(to,0,item);sessions.clear();for(const [id,s] of items)sessions.set(id,s);renderSessions();}
function toggleFloat(session,card){session.floating=!session.floating;if(session.floating){session.minimized=false;card.style.left='12%';card.style.top='12%';card.style.width='70%';card.style.height='70%';card.style.resize='both';}card.classList.toggle('terminal-floating',session.floating);card.classList.toggle('terminal-minimized',session.minimized);session.fit.fit();}
function minimizeSession(session){session.minimized=true;session.floating=false;renderSessions();}
function restoreSession(id){const session=sessions.get(id);if(!session)return;session.minimized=false;activeSession=id;renderSessions();session.term.focus();}
function renderMinimized(){const bar=$('minimized-sessions');if(!bar)return;bar.replaceChildren();for(const s of sessions.values()){if(!s.minimized)continue;const b=document.createElement('button');b.className='button quiet';b.textContent=`● ${s.title}`;b.title='恢复终端';b.addEventListener('click',()=>restoreSession(s.id));bar.append(b);}}
function chooseImage(session){$('image-file').dataset.sessionId=session.id;$('image-file').value='';$('image-file').click();}

function showForm(title, fields, initial, onSave, onDelete=null) {
  $('form-title').textContent=title; $('form-error').textContent='';
  const container=$('form-fields'); container.replaceChildren();
  const inputs={};
  for (const field of fields) {
    const label=document.createElement('label'); label.textContent=field.label;
    const input=document.createElement('input'); input.className='input'; input.type=field.type||'text'; input.placeholder=field.placeholder||'';
    if(input.type==='checkbox') input.checked=Boolean(initial?.[field.key]??field.value); else input.value=initial?.[field.key] ?? field.value ?? ''; input.autocomplete='off'; input.dataset.key=field.key;
    label.append(input); container.append(label); inputs[field.key]=input;
  }
  const actions=formDialog.querySelector('.dialog-actions');
  actions.querySelector('.delete-form')?.remove();
  $('form-browse').hidden=true;
  if (onDelete) { const del=document.createElement('button'); del.type='button'; del.className='button danger-button delete-form'; del.textContent='删除'; del.addEventListener('click',()=>{formDialog.close('delete');onDelete();}); actions.prepend(del); }
  form.onsubmit=(event)=>{
    event.preventDefault();
    const values=Object.fromEntries(Object.entries(inputs).map(([k,v])=>[k,v.type==='checkbox'?v.checked:v.value.trim()]));
    const error=onSave(values);
    if (error) { $('form-error').textContent=error; return; }
    formDialog.close('save');
  };
  formDialog.showModal();
  Object.values(inputs)[0]?.focus();
  return inputs;
}
function editHost(host=null) {
  showForm(host?'编辑主机':'添加主机',[
    {key:'name',label:'名称',placeholder:'开发服务器'},
    {key:'username',label:'SSH 用户名',placeholder:'ubuntu'},
    {key:'lan_address',label:'内网地址（可选）',placeholder:'192.168.1.20'},
    {key:'lan_port',label:'内网端口',value:'22'},
    {key:'external_address',label:'外网地址（可选）',placeholder:'dev.example.com'},
    {key:'external_port',label:'外网端口',value:'22'},
    {key:'sync_id',label:'多端同步标识（可选）',placeholder:'同一主机在各设备使用相同标识'},
    {key:'password',label:'SSH 密码（存入 Windows 凭据管理器）',type:'password',placeholder:host?.credential_saved?'••••••••（已保存，留空保持）':'输入 SSH 密码'},
    {key:'clear_password',label:'删除已保存的 SSH 密码',type:'checkbox'}
  ],{...host,password:'',clear_password:false,lan_port:String(host?.lan_port||host?.port||22),external_port:String(host?.external_port||host?.port||22)},(v)=>{
    if(!v.name || !v.username || (!v.lan_address && !v.external_address)) return '请填写名称、SSH 用户名和至少一个 SSH 地址。';
    for(const key of ['lan_port','external_port']) if((key==='lan_port'?v.lan_address:v.external_address) && (!/^\d+$/.test(v[key]) || +v[key]<1 || +v[key]>65535)) return '端口必须是 1 到 65535 之间的数字。';
    const item=host||{id:makeId(),directories:[],selected_agent:0};
    const password=v.password;delete v.password;const clearPassword=v.clear_password;delete v.clear_password;
    Object.assign(item,v,{lan_port:Number(v.lan_port||22),external_port:Number(v.external_port||22),port:Number(v.lan_port||v.external_port||22),address:v.lan_address||v.external_address});
    if(password&&clearPassword)return '请输入新密码或选择删除密码，不能同时操作。';
    if(password){item.credential_id ||= makeId();post({action:'store-host-password',credential_id:item.credential_id,username:item.username,password});}
    else if(clearPassword&&item.credential_id){post({action:'clear-host-password',credential_id:item.credential_id});delete item.credential_id;}
    if(!host) config.hosts.push(item);
    activeHost=item.id; sendConfig(); render(); return '';
  },host?()=>{if(confirm(`删除主机“${host.name}”及其工作区？`)){if(host.credential_id)post({action:'clear-host-password',credential_id:host.credential_id});post({action:'delete-host-profile',host_id:host.id});config.hosts=config.hosts.filter(x=>x.id!==host.id);activeHost=config.hosts[0]?.id||'';sendConfig();render();}}:null);
}
function editAgent(index=-1) {
  const host=currentHost(); const list=agentsForHost(host); const old=index>=0?list[index]:null;
  showForm(old?'编辑开发者':'添加开发者',[{key:'name',label:'名称',placeholder:'Codex'},{key:'command',label:'启动命令',placeholder:'codex'}],old,(v)=>{
    if(!v.name||!v.command) return '请填写名称和启动命令。';
    if(host && host.agents?.length){ if(old)host.agents[index]=v; else host.agents.push(v); }
    else { if(old)config.agents[index]=v; else config.agents.push(v); }
    if(host) host.selected_agent=old?index:list.length;
    sendConfig();render();return '';
  },old?()=>{if(confirm(`删除开发者“${old.name}”？`)){const target=host&&host.agents?.length?host.agents:config.agents;target.splice(index,1);if(host)host.selected_agent=Math.max(0,index-1);sendConfig();render();}}:null);
}
function editWorkspace(index=-1) {
  const host=currentHost(); if(!host)return;
  const old=index>=0?host.directories[index]:null;
  const controls=showForm(old?'编辑工作区':'添加工作区',[{key:'name',label:'名称',placeholder:'项目名称'},{key:'path',label:'远端绝对路径',placeholder:'/home/user/project'}],old,(v)=>{
    if(!v.name||!v.path.startsWith('/')) return '请填写名称和以 / 开头的远端绝对路径。';
    if(old)host.directories[index]=v;else host.directories.push(v);
    sendConfig();render();return '';
  },old?()=>deleteWorkspace(index):null);
  browseTargetInput=controls.path;$('form-browse').hidden=false;$('form-browse').onclick=()=>{const requestId=makeId();$('browse-path').value=controls.path.value||'~';$('browse-path').dataset.requestId=requestId;$('browse-list').replaceChildren();$('browse-dialog').showModal();post({action:'browse-directories',request_id:requestId,host,path:$('browse-path').value});};
}
function deleteWorkspace(index){const host=currentHost(), item=host?.directories[index];if(!item||!confirm(`删除工作区“${item.name}”？`))return;host.directories.splice(index,1);sendConfig();render();}
function moveWorkspace(index,offset){const host=currentHost(),items=host?.directories;if(!items||index<0||index+offset<0||index+offset>=items.length)return;[items[index],items[index+offset]]=[items[index+offset],items[index]];sendConfig();render();}
function moveAgentAt(index,offset){const host=currentHost(),items=agentsForHost(host),target=index+offset;if(index<0||target<0||target>=items.length)return;[items[index],items[target]]=[items[target],items[index]];if(host)host.selected_agent=target;sendConfig();render();}

function openSession(workspaceIndex, sharedItem=null) {
  const host=currentHost(), agent=currentAgent(), workspace=host?.directories?.[workspaceIndex];
  if(!host||!agent||!workspace){showError('请先配置主机、开发者和工作区。');return;}
  if(sharedItem&&sessions.has(sharedItem.id)){activeSession=sharedItem.id;renderSessions();sessions.get(sharedItem.id).term.focus();return;}
  if(sessions.size>=8){showError('最多同时打开 8 个终端。');return;}
  const id=sharedItem?.id||makeId(), title=sharedItem?.display_name||`${workspace.name||workspace.path} · ${agent.name||agent.command}`;
  const term=new Terminal({cursorBlink:true,fontFamily:'Cascadia Code,Consolas,monospace',fontSize:terminalFontSize(),scrollback:5000,convertEol:false,allowProposedApi:true,theme:terminalTheme()});
  const fit=new FitAddon();term.loadAddon(fit);
  const session={id,title,term,fit,host:{...host},workspace:{...workspace},agent:{...agent},connected:false,opened:false,shared:Boolean(sharedItem)};
  session.autoState={signature:'',since:0,sent:false};
  term.onData(data=>{session.autoState.signature='';session.autoState.since=0;session.autoState.sent=false;post({action:'input',id,data});});
  term.onResize(({cols,rows})=>post({action:'resize',id,cols,rows}));
  session.autoTimer=setInterval(()=>watchAutoEnter(session),250);
  sessions.set(id,session);activeSession=id;renderSessions();term.writeln('\x1b[90m正在连接 SSH…\x1b[0m');
  post({action:'session-sync',method:'PUT',host:session.host,item:{id,directory_path:workspace.path,directory_name:workspace.name,agent_name:agent.name,agent_command:agent.command,display_name:title}});
  connectSession(session);
  return id;
}
function connectSession(session) {
  session.connected=false;setBusy(true);
  post({action:'connect',id:session.id,host:session.host,workspace:session.workspace,agent:session.agent,cols:session.term.cols,rows:session.term.rows});
}
function closeSession(id) {
  const session=sessions.get(id);if(!session)return;
  post({action:'session-sync',method:'DELETE',host:session.host,id});
  post({action:'close',id});clearInterval(session.autoTimer);session.term.dispose();sessions.delete(id);
  if(activeSession===id)activeSession=sessions.keys().next().value||'';
  setBusy([...sessions.values()].some(s=>s.connected));renderSessions();
}
function handleNativeMessage(event) {
  const msg=event.data;
  if(msg.type==='config'){normalizeConfig(msg.config);return;}
  if(msg.type==='sync-auth-result'){if(msg.auth||(!msg.success&&msg.manual))showError(msg.message);else if(msg.success&&msg.manual)$('connection-status').title=msg.message;else if(!msg.success)$('connection-status').title=`同步失败：${msg.message}`;return;}
  if(msg.type==='shared-sessions'){renderSharedSessions(msg.host_id,msg.host,msg.sessions||[]);return;}
  if(msg.type==='admin-users'){renderAdminUsers(msg.users||[]);return;}
  if(msg.type==='browse-result'){renderBrowse(msg);return;}
  if(msg.type==='host-metrics'){metricsByHost.set(msg.host_id,{memory:Number(msg.memory),cpu:Number(msg.cpu)});renderHosts();return;}
  if(msg.type==='host-endpoint'){const host=config.hosts.find(item=>item.id===msg.host_id);if(host&&sshEndpointSignature(host)===msg.signature){sshEndpoints.set(msg.host_id,{signature:msg.signature,address:msg.address,port:Number(msg.port)||22});renderHosts();}return;}
  if(msg.type==='agent-quota'){quotaByAgent.set(quotaKey(msg.host_id||activeHost,msg.command),{windows:msg.windows||[],error:msg.error||''});renderAgents();return;}
  if(msg.type==='admin-result'){if(!msg.success)showError(msg.message);else post({action:'admin-users'});return;}
  if(msg.type==='mcp-info'){showError(msg.message);return;}
  if(msg.type==='mcp-tool'){void executeMcpRequest(msg);return;}
  if(msg.type==='mcp-image-result'||msg.type==='mcp-public-key'){const pending=pendingMcp.get(msg.request_id);if(pending){pendingMcp.delete(msg.request_id);msg.error?pending.reject(new Error(msg.error)):pending.resolve(msg.result);}return;}
  const session=sessions.get(msg.id);
  if(msg.type==='output'&&session){const raw=atob(msg.data);const bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));session.term.write(bytes);}
  else if(msg.type==='endpoint-selected'&&session){const address=String(msg.address||'').replace(/[\x00-\x1f\x7f]/g,'');session.term.writeln(`\r\n\x1b[90m连接目标 ${address}:${Number(msg.port)||22} …\x1b[0m`);}
  else if(msg.type==='connected'&&session){session.connected=true;setBusy(false);session.term.focus();}
  else if(msg.type==='closed'&&session){session.connected=false;setBusy([...sessions.values()].some(s=>s.connected));session.term.writeln(`\r\n\x1b[90m[SSH 已断开，退出码 ${Number(msg.code)}；点击右上角 ↻ 重新连接]\x1b[0m`);}
  else if(msg.type==='error'&&session){session.connected=false;setBusy(false);session.term.writeln(`\r\n\x1b[31m${msg.message}\x1b[0m`);}
  else if(msg.type==='saved'){ $('connection-status').title='配置已保存'; }
  else if(msg.type==='notice')showError(msg.message);
}

function renderSharedSessions(hostId,host,items){const root=$('shared-list');root.replaceChildren();if(!items.length){const p=document.createElement('p');p.className='muted';p.textContent='暂无共享会话';root.append(p);}for(const item of items){const b=document.createElement('button');b.className='button';b.textContent=item.display_name||`${item.directory_name} · ${item.agent_name}`;b.addEventListener('click',()=>{const h=config.hosts.find(x=>x.id===hostId)||host;if(!h)return;activeHost=h.id;let wi=(h.directories||[]).findIndex(w=>w.path===item.directory_path);if(wi<0){h.directories||=[];h.directories.push({name:item.directory_name,path:item.directory_path});wi=h.directories.length-1;}let agents=agentsForHost(h),ai=agents.findIndex(a=>a.command===item.agent_command);if(ai<0){config.agents.push({name:item.agent_name,command:item.agent_command});agents=config.agents;ai=agents.length-1;}h.selected_agent=ai;sendConfig();render();$('shared-dialog').close();openSession(wi,item);});root.append(b);}$('shared-dialog').showModal();}
function renderAdminUsers(users){const root=$('admin-users-list');root.replaceChildren();for(const user of users){const row=document.createElement('div');row.className='settings-row';const label=document.createElement('span');label.textContent=`${user.username} · ${user.is_admin?'管理员':'普通用户'}`;const actions=document.createElement('span');const edit=document.createElement('button');edit.className='button';edit.type='button';edit.textContent='编辑';edit.addEventListener('click',()=>adminUserForm(user));const del=document.createElement('button');del.className='button';del.type='button';del.textContent='删除';del.addEventListener('click',()=>{if(confirm(`删除账号 ${user.username} 及其同步资料？`))post({action:'admin-user-delete',user_id:user.id});});actions.append(edit,del);row.append(label,actions);root.append(row);}$('admin-users-dialog').showModal();}
function adminUserForm(user=null){showForm(user?'编辑同步账号':'添加同步账号',[{key:'username',label:'用户名'},{key:'password',label:'密码（编辑时留空以保留）',type:'password'},{key:'is_admin',label:'管理员',type:'checkbox'}],user,(v)=>{if(!/^[a-z0-9_.-]{3,64}$/i.test(v.username))return '用户名需为 3–64 位字母、数字、点、下划线或连字符。';if(!user&&v.password.length<8)return '密码至少 8 个字符。';post({action:'admin-user-save',user_id:user?.id,username:v.username,password:v.password,is_admin:v.is_admin});return '';});}
function requestBrowse(path){const requestId=$('browse-path').dataset.requestId;post({action:'browse-directories',request_id:requestId,host:currentHost(),path:path||'~'});}
function renderBrowse(msg){const pending=pendingMcp.get(msg.request_id);if(pending){pendingMcp.delete(msg.request_id);msg.error?pending.reject(new Error(msg.error)):pending.resolve({path:msg.current,directories:msg.directories||[]});return;}if(msg.request_id!==$('browse-path').dataset.requestId)return;const root=$('browse-list');root.replaceChildren();if(msg.error){const p=document.createElement('p');p.className='form-error';p.textContent=msg.error;root.append(p);return;}$('browse-path').value=msg.current||'/';for(const entry of msg.directories||[]){const b=document.createElement('button');b.type='button';b.className='button';b.textContent=`📁 ${entry.name}`;b.title=entry.path;b.addEventListener('click',()=>{ $('browse-path').value=entry.path;requestBrowse(entry.path);});root.append(b);}}

async function executeMcpRequest(message){try{const result=await executeMcpTool(message.name,message.arguments||{});post({action:'mcp-result',id:message.id,result});}catch(error){post({action:'mcp-result',id:message.id,error:String(error?.message||error)});}}
function argText(a,key,fallback=''){return a[key]===undefined||a[key]===null?fallback:String(a[key]);}
function resolveMcpHost(a){const list=config.hosts;let index=-1;if(a.host_id)index=list.findIndex(h=>h.id===a.host_id);else if(a.host_index!==undefined)index=Number(a.host_index);else if(a.host_name)index=list.findIndex(h=>h.name===a.host_name);else index=list.findIndex(h=>h.id===activeHost);if(index<0||index>=list.length)throw new Error('未找到指定主机');return [index,list[index]];}
function resolveMcpAgent(host,a){const list=agentsForHost(host);let index=a.agent_index!==undefined?Number(a.agent_index):a.agent_name?list.findIndex(x=>x.name===a.agent_name):Math.max(0,Number(host.selected_agent)||0);if(index<0||index>=list.length)throw new Error('未找到指定 Agent');return [index,list[index]];}
function resolveMcpWorkspace(host,a){const list=host.directories||[];let index=a.workspace_index!==undefined?Number(a.workspace_index):a.workspace_path?list.findIndex(x=>x.path===a.workspace_path):a.workspace_name?list.findIndex(x=>x.name===a.workspace_name):0;if(index<0||index>=list.length)throw new Error('未找到指定工作区');return [index,list[index]];}
function resolveMcpSession(a){const list=[...sessions.values()];let index=a.session_id?list.findIndex(s=>s.id===a.session_id):a.session_index!==undefined?Number(a.session_index):list.findIndex(s=>s.id===activeSession);if(index<0||index>=list.length)throw new Error('未找到指定终端会话');return [index,list[index]];}
function sessionState(s,index){return {index,session_id:s.id,display_name:s.title,host_id:s.host.id,host_name:s.host.name,directory_name:s.workspace.name,directory_path:s.workspace.path,agent_name:s.agent.name,agent_command:s.agent.command,connected:s.connected,view_mode:viewMode,minimized:Boolean(s.minimized),floating:Boolean(s.floating)};}
async function executeMcpTool(name,a){
  if(name==='get_app_state')return {platform:'windows',version:'1.0.0',tiled:viewMode==='grid',tile_layout:tileLayout,ui_scale:Number($('scale').value),sidebar_visible:!document.body.classList.contains('sidebar-hidden'),sidebar_width:document.querySelector('.sidebar').getBoundingClientRect().width,edit_mode:editing,auto_enter:Boolean(config.auto_enter),auto_enter_delay:Number(config.auto_enter_delay)||1000,auto_sync_enabled:Boolean(config.auto_sync_enabled),sync_interval_seconds:Number(config.sync_interval_seconds)||5,sync_url:config.sync_url||'',sync_username:config.sync_username||'',current_host_index:config.hosts.findIndex(h=>h.id===activeHost),current_agent_index:currentHost()?.selected_agent||0,hosts:config.hosts.map((h,index)=>({index,id:h.id,name:h.name,username:h.username,lan_address:h.lan_address||'',lan_port:h.lan_port,external_address:h.external_address||'',external_port:h.external_port,port:h.port,sync_id:h.sync_id||'',has_credential:Boolean(h.credential_id),selected_agent:h.selected_agent||0,directories:(h.directories||[]).map((w,index)=>({index,name:w.name,path:w.path})),agents:agentsForHost(h).map((agent,index)=>({index,name:agent.name,command:agent.command}))})),sessions:[...sessions.values()].map(sessionState)};
  if(name==='select_host'){const [index,host]=resolveMcpHost(a);activeHost=host.id;sendConfig();render();return {selected_host_index:index,host:{id:host.id,name:host.name,username:host.username}};}
  if(['add_host','edit_host','delete_host'].includes(name)){
    if(name==='delete_host'){const [index,host]=resolveMcpHost(a);if(host.credential_id)post({action:'clear-host-password',credential_id:host.credential_id});post({action:'delete-host-profile',host_id:host.id});config.hosts.splice(index,1);activeHost=config.hosts[0]?.id||'';sendConfig();render();return {deleted_host_id:host.id,deleted_host_name:host.name};}
    const old=name==='edit_host'?resolveMcpHost(a)[1]:null;const v={name:argText(a,'name',old?.name||'').trim(),username:argText(a,'username',old?.username||'').trim(),lan_address:argText(a,'lan_address',old?.lan_address||''),lan_port:Number(a.lan_port??old?.lan_port??22),external_address:argText(a,'external_address',old?.external_address||''),external_port:Number(a.external_port??old?.external_port??22),sync_id:argText(a,'sync_id',old?.sync_id||'')};if(!v.name||!v.username||(!v.lan_address&&!v.external_address))throw new Error('请填写名称、SSH 用户名和至少一个地址');if([v.lan_address,v.external_address].some((address,i)=>address&&!Number.isInteger(i?v.external_port:v.lan_port)))throw new Error('主机端口必须是有效整数');const host=old||{id:makeId(),directories:[],selected_agent:0};Object.assign(host,v,{port:v.lan_address?v.lan_port:v.external_port,address:v.lan_address||v.external_address});if(a.password){host.credential_id ||= makeId();post({action:'store-host-password',credential_id:host.credential_id,username:host.username,password:String(a.password)});}if(a.clear_password&&host.credential_id){post({action:'clear-host-password',credential_id:host.credential_id});delete host.credential_id;}if(!old)config.hosts.push(host);activeHost=host.id;sendConfig();render();return {host:{id:host.id,name:host.name,username:host.username}};
  }
  if(name==='select_agent'){const [hostIndex,host]=resolveMcpHost(a);activeHost=host.id;const [index,agent]=resolveMcpAgent(host,a);host.selected_agent=index;sendConfig();render();return {host_index:hostIndex,agent_index:index,agent};}
  if(['add_agent','edit_agent','delete_agent'].includes(name)){const [hostIndex,host]=resolveMcpHost(a),list=agentsForHost(host);let index=a.agent_index!==undefined?Number(a.agent_index):a.agent_name?list.findIndex(x=>x.name===a.agent_name):-1;if(name==='add_agent'){const agent={name:argText(a,'name').trim(),command:argText(a,'command').trim()};if(!agent.name||!agent.command)throw new Error('Agent 名称和命令不能为空');list.push(agent);host.selected_agent=list.length-1;sendConfig();render();return {host_index:hostIndex,agent_index:list.length-1,agent};}if(index<0||index>=list.length)throw new Error('未找到指定 Agent');if(name==='delete_agent'){const [deleted]=list.splice(index,1);host.selected_agent=Math.min(index,Math.max(0,list.length-1));sendConfig();render();return {host_index:hostIndex,deleted_agent:deleted};}const updated={...list[index],name:argText(a,'name',list[index].name).trim(),command:argText(a,'command',list[index].command).trim()};if(!updated.name||!updated.command)throw new Error('Agent 名称和命令不能为空');list[index]=updated;sendConfig();render();return {host_index:hostIndex,agent_index:index,agent:updated};}
  if(['add_workspace','edit_workspace','delete_workspace','move_workspace'].includes(name)){const [hostIndex,host]=resolveMcpHost(a);host.directories||=[];let index=a.workspace_index!==undefined?Number(a.workspace_index):a.workspace_path?host.directories.findIndex(w=>w.path===a.workspace_path):a.workspace_name?host.directories.findIndex(w=>w.name===a.workspace_name):-1;if(name==='add_workspace'){const workspace={name:argText(a,'name').trim(),path:argText(a,'path').trim()};if(!workspace.name||!workspace.path.startsWith('/'))throw new Error('工作区需要名称和以 / 开头的绝对路径');host.directories.push(workspace);sendConfig();render();return {host_index:hostIndex,workspace_index:host.directories.length-1,workspace};}if(index<0||index>=host.directories.length)throw new Error('未找到指定工作区');if(name==='delete_workspace'){const [deleted]=host.directories.splice(index,1);sendConfig();render();return {host_index:hostIndex,deleted_workspace:deleted};}if(name==='move_workspace'){const target=Math.max(0,Math.min(host.directories.length-1,index+Number(a.offset||0)));[host.directories[index],host.directories[target]]=[host.directories[target],host.directories[index]];sendConfig();render();return {host_index:hostIndex,directories:host.directories};}const updated={...host.directories[index],name:argText(a,'name',host.directories[index].name).trim(),path:argText(a,'path',host.directories[index].path).trim()};if(!updated.name||!updated.path.startsWith('/'))throw new Error('工作区名称和绝对路径无效');host.directories[index]=updated;sendConfig();render();return {host_index:hostIndex,workspace_index:index,workspace:updated};}
  if(name==='browse_remote_directory'){const [,host]=resolveMcpHost(a);return await new Promise((resolve,reject)=>{const request_id=makeId();pendingMcp.set(request_id,{resolve,reject});post({action:'browse-directories',request_id,host,path:argText(a,'path','~')});});}
  if(name==='open_terminal'){const [,host]=resolveMcpHost(a);const [,agent]=resolveMcpAgent(host,a);const [,workspace]=resolveMcpWorkspace(host,a);activeHost=host.id;host.selected_agent=agentsForHost(host).indexOf(agent);sendConfig();render();const id=openSession(host.directories.indexOf(workspace));if(!id)throw new Error('无法打开终端，可能已达到 8 个会话上限');return {session:sessionState(sessions.get(id),[...sessions.keys()].indexOf(id))};}
  if(name==='focus_terminal'){const [index,s]=resolveMcpSession(a);activeSession=s.id;s.minimized=false;renderSessions();s.term.focus();return {session:sessionState(s,index)};}
  if(name==='close_terminal'){const [index,s]=resolveMcpSession(a);closeSession(s.id);return {closed_session_id:s.id,closed_index:index,remaining_sessions:sessions.size};}
  if(name==='reconnect_terminal'){const [index,s]=resolveMcpSession(a);connectSession(s);return {reconnecting_session_id:s.id,session_index:index};}
  if(name==='send_terminal_text'){const [index,s]=resolveMcpSession(a);if(a.focus!==false){activeSession=s.id;s.term.focus();}const text=String(a.text??'');const paste=a.use_paste??text.includes('\n');post({action:'input',id:s.id,data:paste?`\x1b[200~${text}\x1b[201~`:text});if(a.submit!==false)post({action:'input',id:s.id,data:'\r'});return {sent:true,session_id:s.id,bytes:new TextEncoder().encode(text).length,submitted:a.submit!==false,index};}
  if(name==='send_terminal_keys'){const [index,s]=resolveMcpSession(a),keys=Array.isArray(a.keys)?a.keys:[a.keys];const map={enter:'\r',return:'\r',tab:'\t','shift+tab':'\x1b[Z',escape:'\x1b',esc:'\x1b',backspace:'\x7f',delete:'\x1b[3~',space:' ',up:'\x1b[A',down:'\x1b[B',right:'\x1b[C',left:'\x1b[D',home:'\x1b[H',end:'\x1b[F',pageup:'\x1b[5~',page_up:'\x1b[5~',pagedown:'\x1b[6~',page_down:'\x1b[6~'};let payload='';for(const raw of keys){const key=String(raw).trim().toLowerCase();if(map[key])payload+=map[key];else if(/^ctrl\+[a-z]$/.test(key))payload+=String.fromCharCode(key.charCodeAt(5)-96);else throw new Error(`不支持的按键：${raw}`);}if(a.focus!==false){activeSession=s.id;s.term.focus();}post({action:'input',id:s.id,data:payload});return {sent:true,session_id:s.id,bytes:new TextEncoder().encode(payload).length,index};}
  if(name==='read_terminal_screen'){const [index,s]=resolveMcpSession(a),buffer=s.term.buffer.active,max=Math.max(1,Math.min(5000,Number(a.max_lines)||200)),end=buffer.length,start=a.include_scrollback?Math.max(0,end-max):Math.max(0,buffer.viewportY??buffer.baseY??0),lines=[];for(let row=start;row<Math.min(end,start+max);row++)lines.push(buffer.getLine(row)?.translateToString(true)||'');return {text:lines.join('\n'),cursor:{x:buffer.cursorX,y:buffer.cursorY},session:sessionState(s,index)};}
  if(name==='send_image_to_terminal'){const [,s]=resolveMcpSession(a);return await new Promise((resolve,reject)=>{const request_id=makeId();pendingMcp.set(request_id,{resolve,reject});post({action:'upload-image-path',request_id,id:s.id,image_path:a.image_path,prompt_prefix:argText(a,'prompt_prefix','请查看这张图片：')});});}
  if(name==='set_view_mode'){viewMode=a.tiled?'grid':'tabs';config.tiled=Boolean(a.tiled);sendConfig();renderSessions();return {tiled:Boolean(a.tiled)};}
  if(name==='set_tile_layout'){if(!['grid','columns','rows'].includes(a.layout))throw new Error('layout 必须为 grid、columns 或 rows');tileLayout=a.layout;sendConfig();renderSessions();return {tile_layout:tileLayout};}
  if(name==='toggle_sidebar'){if(a.visible!==undefined){config.sidebar_hidden=!a.visible;document.body.classList.toggle('sidebar-hidden',!a.visible);fitSessions();sendConfig();}if(a.width!==undefined){config.sidebar_width=Math.max(sidebarMinimumWidth(),Math.min(2000,Number(a.width)));document.querySelector('.sidebar').style.width=`${config.sidebar_width}px`;fitSessions();sendConfig();}if(a.edit_mode!==undefined){editing=Boolean(a.edit_mode);updateEditing();}return {sidebar_visible:!document.body.classList.contains('sidebar-hidden'),sidebar_width:document.querySelector('.sidebar').getBoundingClientRect().width,edit_mode:editing};}
  if(name==='set_ui_scale'){const scale=Number(a.scale);if(!Number.isInteger(scale)||scale<50||scale>200)throw new Error('scale 必须在 50 到 200 之间');applyUiScale(scale);sendConfig();return {ui_scale:scale};}
  if(name==='update_settings'){for(const key of ['auto_enter','auto_sync_enabled'])if(a[key]!==undefined)config[key]=Boolean(a[key]);for(const key of ['auto_enter_delay','sync_interval_seconds'])if(a[key]!==undefined)config[key]=Number(a[key]);if(a.sync_url!==undefined&&config.sync_url!==a.sync_url){config.sync_url=String(a.sync_url).trim().replace(/\/$/,'');config.sync_token='';}sendConfig();return {auto_enter:Boolean(config.auto_enter),auto_enter_delay:Number(config.auto_enter_delay)||1000,auto_sync_enabled:Boolean(config.auto_sync_enabled),sync_interval_seconds:Number(config.sync_interval_seconds)||5,sync_url:config.sync_url||''};}
  if(name==='trigger_sync'){post({action:'sync-now'});return {triggered:true};}
  if(name==='get_ssh_public_key'){return await new Promise((resolve,reject)=>{const request_id=makeId();pendingMcp.set(request_id,{resolve,reject});post({action:'get-ssh-public-key',request_id,generate_if_missing:Boolean(a.generate_if_missing)});});}
  throw new Error(`未知 MCP 工具：${name}`);
}

$('add-host').addEventListener('click',()=>editHost());
$('add-agent').addEventListener('click',()=>editAgent());
$('add-workspace').addEventListener('click',()=>editWorkspace()); $('empty-add-host').addEventListener('click',()=>editHost());
$('edit-mode').addEventListener('click',()=>{editing=!editing;updateEditing();});
$('sidebar-toggle').addEventListener('click',()=>{config.sidebar_hidden=!document.body.classList.contains('sidebar-hidden');document.body.classList.toggle('sidebar-hidden',config.sidebar_hidden);fitSessions();sendConfig();});
const sidebarResizer=$('sidebar-resizer');
let resizingSidebar=false;
sidebarResizer.addEventListener('pointerdown',event=>{if(document.body.classList.contains('sidebar-hidden'))return;resizingSidebar=true;sidebarResizer.setPointerCapture(event.pointerId);document.body.classList.add('resizing-sidebar');event.preventDefault();});
sidebarResizer.addEventListener('pointermove',event=>{if(!resizingSidebar)return;config.sidebar_width=Math.max(sidebarMinimumWidth(),Math.min(2000,window.innerWidth*.65,event.clientX));document.querySelector('.sidebar').style.width=`${config.sidebar_width}px`;});
sidebarResizer.addEventListener('pointerup',()=>{if(!resizingSidebar)return;resizingSidebar=false;document.body.classList.remove('resizing-sidebar');fitSessions();sendConfig();});
sidebarResizer.addEventListener('lostpointercapture',()=>{resizingSidebar=false;document.body.classList.remove('resizing-sidebar');});
$('view-mode').addEventListener('click',()=>{viewMode=viewMode==='grid'?'tabs':'grid';sendConfig();renderSessions();});
$('layout-grid').addEventListener('click',()=>{tileLayout=tileLayout==='grid'?'columns':tileLayout==='columns'?'rows':'grid';sendConfig();renderSessions();});
$('scale').addEventListener('input',()=>applyUiScale());
$('scale').addEventListener('change',sendConfig);
for(const [id,theme] of Object.entries(THEMES)) $('theme-select').add(new Option(theme.label,id));
$('settings').addEventListener('click',()=>{$('auto-enter').checked=Boolean(config.auto_enter);$('settings-dialog').showModal();});
$('theme-select').addEventListener('change',()=>{config.theme=$('theme-select').value;applyTheme();sendConfig();});
$('settings-save').addEventListener('click',(event)=>{event.preventDefault();const oldUrl=config.sync_url||'';config.auto_enter=$('auto-enter').checked;config.auto_enter_delay=Math.max(50,Math.min(30000,Number($('auto-enter-delay').value)||1000));config.agent_quota_interval_seconds=Math.max(15,Math.min(300,Number($('quota-interval').value)||60));config.host_metrics_interval_seconds=Math.max(5,Math.min(300,Number($('metrics-interval').value)||5));config.sync_interval_seconds=Math.max(5,Math.min(300,Number($('sync-interval').value)||5));config.sync_url=$('sync-url').value.trim().replace(/\/$/,'');config.auto_sync_enabled=$('auto-sync').checked;config.offline_mode=$('offline-mode').checked;if(oldUrl!==config.sync_url)config.sync_token='';sendConfig();$('settings-dialog').close('save');});
$('sync-login').addEventListener('click',()=>{const username=prompt('同步账号',config.sync_username||'');if(!username)return;const password=prompt('同步密码');if(password===null)return;post({action:'sync-auth',mode:'login',username:username.trim(),password});});
$('sync-logout').addEventListener('click',()=>post({action:'sync-auth',mode:'logout'}));
$('sync-now').addEventListener('click',()=>post({action:'sync-now'}));
$('shared-sessions').addEventListener('click',()=>{const host=currentHost();if(!host)return showError('请先选择主机。');post({action:'get-shared-sessions',host_id:host.id,host});});
$('mcp-guide').addEventListener('click',()=>post({action:'mcp-info'}));
$('sync-admin-users').addEventListener('click',()=>post({action:'admin-users'}));
$('admin-user-add').addEventListener('click',()=>adminUserForm());
$('browse-go').addEventListener('click',()=>requestBrowse($('browse-path').value.trim()||'~'));
$('browse-select').addEventListener('click',()=>{if(browseTargetInput)browseTargetInput.value=$('browse-path').value.trim();$('browse-dialog').close();});
$('image-file').addEventListener('change',async event=>{const file=event.target.files?.[0],session=sessions.get(event.target.dataset.sessionId);if(!file||!session)return;try{const data=await imageData(file);post({action:'upload-image',id:session.id,data});}catch(error){showError(error.message);}});
async function imageData(file){let img=new Image();img.src=URL.createObjectURL(file);await img.decode();let width=img.naturalWidth,height=img.naturalHeight,scale=Math.min(1,4096/Math.max(width,height));width=Math.max(1,Math.round(width*scale));height=Math.max(1,Math.round(height*scale));const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;canvas.getContext('2d').drawImage(img,0,0,width,height);URL.revokeObjectURL(img.src);let quality=.92;let blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',quality));if(!blob)throw new Error('无法转换此图片格式');while(blob.size>20*1024*1024&&quality>.35){quality-=.1;blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',quality));if(!blob)throw new Error('无法转换此图片格式');}if(blob.size>20*1024*1024)throw new Error('图片转换后仍超过 20 MB');return await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=()=>reject(reader.error);reader.readAsDataURL(blob);});}
document.addEventListener('paste',event=>{const item=[...(event.clipboardData?.items||[])].find(x=>x.type.startsWith('image/'));if(!item||!activeSession)return;const file=item.getAsFile();if(!file)return;event.preventDefault();imageData(file).then(data=>post({action:'upload-image',id:activeSession,data})).catch(error=>showError(error.message));});
window.chrome?.webview?.addEventListener('message',handleNativeMessage);
window.addEventListener('resize',()=>{for(const s of sessions.values()){try{s.fit.fit();}catch{} }});
if(window.chrome?.webview)post({action:'load-config'});
else $('empty-state').querySelector('p').textContent='请从 Remote Dev Terminal Windows 程序启动此界面。';
