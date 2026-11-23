import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildSessionSyncItem,
  findExistingSession,
  formatSessionTitle,
  isValidSessionId,
  mergeSessionSyncItems,
  normalizeSessionId,
  planSyncedSessionChanges,
} from '../windows/native/web/src/session-sync.js';
import {
  clampFloatingRect,
  computeDraggedFloatingRect,
  defaultFloatingRect,
} from '../windows/native/web/src/floating-layout.js';

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// 1. Session ID normalization and payload completeness
assert.equal(normalizeSessionId('AABBCCDD-1122-3344-5566-77889900AABB'), 'aabbccdd11223344556677889900aabb');
assert.equal(isValidSessionId('aabbccdd-1122-3344-5566-77889900aabb'), true);
assert.equal(isValidSessionId('not-a-valid-id'), false);

const syncItem = buildSessionSyncItem({
  id: 'AABBCCDD-1122-3344-5566-77889900AABB',
  workspace: { name: '', path: '/home/dev/app' },
  agent: { name: '', command: 'codex' },
  title: '',
});
assert.deepEqual(syncItem, {
  id: 'aabbccdd11223344556677889900aabb',
  directory_path: '/home/dev/app',
  directory_name: '/home/dev/app',
  agent_name: 'codex',
  agent_command: 'codex',
  display_name: 'codex · /home/dev/app',
});
assert.equal(formatSessionTitle({
  workspace: { name: 'project', path: '/project' },
  agent: { name: 'Codex', command: 'codex' },
  title: 'project · Codex',
}), 'Codex · project');
assert.equal(formatSessionTitle({
  workspace: { name: 'project', path: '/project' },
  agent: { name: 'Codex', command: 'codex' },
  title: 'My custom session',
}), 'My custom session');

// 2. Merge remote items with pending PUT/DELETE mutations and deduplicate by workspace
const id1 = '1'.repeat(32);
const id2 = '2'.repeat(32);
const id3 = '3'.repeat(32);
const id4 = '4'.repeat(32);
const merged = mergeSessionSyncItems(
  [
    { id: id1, directory_path: '/proj/a', directory_name: 'a', agent_name: 'Codex', agent_command: 'codex', display_name: 'a · Codex' },
    { id: id2, directory_path: '/proj/b', directory_name: 'b', agent_name: 'Shell', agent_command: 'bash -l', display_name: 'b · Shell' },
  ],
  [
    { session_id: id2, method: 'DELETE' },
    {
      session_id: id3,
      method: 'PUT',
      item: { id: id3, directory_path: '/proj/a', directory_name: 'a', agent_name: 'Codex', agent_command: 'codex', display_name: 'a · renamed' },
    },
    {
      session_id: id4,
      method: 'PUT',
      item: { id: id4, directory_path: '/proj/c', directory_name: 'c', agent_name: 'Agy', agent_command: 'agy', display_name: 'c · Agy' },
    },
  ],
);
assert.equal(merged.length, 2);
assert.deepEqual(merged.map((x) => x.id), [id3, id4]);
assert.equal(merged[0].display_name, 'a · renamed');

// 3. findExistingSession matches by id, remoteId, or (hostId, directoryPath, agentCommand)
const localSession = {
  id: id1,
  remoteId: id3,
  host: { id: 'host-1' },
  workspace: { path: '/proj/a' },
  agent: { command: 'codex' },
};
assert.equal(findExistingSession([localSession], { id: id1 }), localSession);
assert.equal(findExistingSession([localSession], { id: id3 }), localSession);
assert.equal(
  findExistingSession([localSession], { hostId: 'host-1', directoryPath: '/proj/a', agentCommand: 'codex' }),
  localSession,
);
assert.equal(
  findExistingSession([localSession], { hostId: 'host-2', directoryPath: '/proj/a', agentCommand: 'codex' }),
  null,
);

// 4. planSyncedSessionChanges opens remote sessions, updates names & host remaps, closes removed sessions after grace
const hostCanon = { id: 'canon-host', name: 'DevBox', username: 'dev', lan_address: '10.0.0.5', lan_port: 22 };
const now = 100_000;
const plan = planSyncedSessionChanges({
  currentSessions: [
    // Old session matching remote item id1 with updated display_name and remapped host ID
    {
      id: id1,
      remoteId: id1,
      host: { id: 'old-host', name: 'Old', username: 'dev' },
      workspace: { path: '/proj/a' },
      agent: { command: 'codex' },
      title: 'Old Title',
      startedAt: now - 30_000,
    },
    // Old session removed remotely -> should close after 15s grace
    {
      id: id2,
      remoteId: id2,
      host: hostCanon,
      workspace: { path: '/proj/removed' },
      agent: { command: 'bash -l' },
      title: 'Removed',
      startedAt: now - 30_000,
    },
    // Brand-new local session (<15s) not yet on remote -> keep during grace period
    {
      id: id3,
      remoteId: id3,
      host: hostCanon,
      workspace: { path: '/proj/new-local' },
      agent: { command: 'agy' },
      title: 'Local Grace',
      startedAt: now - 2_000,
    },
  ],
  hosts: [hostCanon],
  syncedHosts: {
    'canon-host': [
      { id: id1, directory_path: '/proj/a', directory_name: 'a', agent_name: 'Codex', agent_command: 'codex', display_name: 'Remote Renamed' },
      { id: id4, directory_path: '/proj/remote-only', directory_name: 'remote-only', agent_name: 'Shell', agent_command: 'bash -l', display_name: 'remote-only · Shell' },
    ],
  },
  remappedHosts: { 'old-host': 'canon-host' },
  now,
});

assert.equal(plan.hostUpdates.length, 1);
assert.equal(plan.hostUpdates[0].sessionId, id1);
assert.equal(plan.hostUpdates[0].host.id, 'canon-host');
assert.deepEqual(plan.renames, [{ sessionId: id1, remoteId: id1, title: 'Remote Renamed' }]);
assert.deepEqual(plan.toClose, [id2]);
assert.equal(plan.toOpen.length, 1);
assert.equal(plan.toOpen[0].host.id, 'canon-host');
assert.equal(plan.toOpen[0].item.id, id4);

// 5. Respects maxSessions = 8 when closing + opening in the same sync pass
const eightIds = Array.from({ length: 8 }, (_, idx) => String(idx + 1).repeat(32));
const capacityPlan = planSyncedSessionChanges({
  currentSessions: eightIds.map((sid, idx) => ({
    id: sid,
    remoteId: sid,
    host: hostCanon,
    workspace: { path: `/proj/${idx}` },
    agent: { command: 'codex' },
    title: `S${idx}`,
    startedAt: now - 30_000,
  })),
  hosts: [hostCanon],
  syncedHosts: {
    'canon-host': [
      ...eightIds.slice(1).map((sid, idx) => ({
        id: sid,
        directory_path: `/proj/${idx + 1}`,
        directory_name: `${idx + 1}`,
        agent_name: 'Codex',
        agent_command: 'codex',
        display_name: `S${idx + 1}`,
      })),
      {
        id: '9'.repeat(32),
        directory_path: '/proj/new',
        directory_name: 'new',
        agent_name: 'Codex',
        agent_command: 'codex',
        display_name: 'S9',
      },
    ],
  },
  now,
});
assert.deepEqual(capacityPlan.toClose, [eightIds[0]]);
assert.equal(capacityPlan.toOpen.length, 1);
assert.equal(capacityPlan.toOpen[0].item.id, '9'.repeat(32));

// 6. CSS checks for top-right zoom step buttons and floating header drag cursor
const layoutCss = fs.readFileSync(path.join(rootDir, 'windows/native/www/layout.css'), 'utf8');
const windowsUi = fs.readFileSync(path.join(rootDir, 'windows/native/web/src/main.js'), 'utf8');
const scaleCss = fs.readFileSync(path.join(rootDir, 'windows/native/www/scale.css'), 'utf8');
assert.match(layoutCss, /\.scale-control\s+\.scale-step\s*\{[^}]*display:\s*inline-flex;[^}]*align-items:\s*center;[^}]*justify-content:\s*center;[^}]*padding:\s*0;/s);
assert.match(scaleCss, /\.toolbar\s+\.scale-control\s+\.scale-step\s*\{[^}]*padding:\s*0;/s);
assert.match(layoutCss, /\.terminal-card\.terminal-floating\s+\.terminal-head\s*\{[^}]*cursor:\s*move;/s);
assert.match(layoutCss, /\.terminal-card-title\s*\{[^}]*flex:\s*0 1 auto;[^}]*max-width:\s*45%;[^}]*overflow:\s*visible;/s);
assert.match(layoutCss, /\.terminal-card-name\s*\{[^}]*max-width:\s*36ch;/s);
assert.match(windowsUi, /formatSessionTitle\(\{workspace,agent\}\)/);
assert.match(windowsUi, /card\.querySelector\('\.terminal-close\.minimize-session'\)\.hidden = viewMode === 'tabs'/);
assert.match(windowsUi, /if \(viewMode === 'grid'\) actionItems\.push\(\['✎', '编辑实例名称'/);
assert.match(windowsUi, /actionItems\.push\(\['×', '关闭终端'/);
assert.match(layoutCss, /body\.view-tabs \.terminal-quota:not\(\[hidden\]\)\s*\{[^}]*display:\s*inline-flex;/s);

// 7. Floating header drag & geometry clamping checks
const firstFloat = defaultFloatingRect(1000, 700, 0);
const secondFloat = defaultFloatingRect(1000, 700, 1);
assert.equal(secondFloat.left - firstFloat.left, 24);
assert.equal(secondFloat.top - firstFloat.top, 24);

const moved = computeDraggedFloatingRect({
  originLeft: firstFloat.left,
  originTop: firstFloat.top,
  deltaX: 80,
  deltaY: -40,
  width: firstFloat.width,
  height: firstFloat.height,
  stageWidth: 1000,
  stageHeight: 700,
  headHeight: 29,
});
assert.equal(moved.left, firstFloat.left + 80);
assert.equal(moved.top, firstFloat.top - 40);

// Prevents dragging header above toolbar (top < 0) or completely off-screen
const clampedEdges = computeDraggedFloatingRect({
  originLeft: 100,
  originTop: 100,
  deltaX: 5000,
  deltaY: -500,
  width: 600,
  height: 400,
  stageWidth: 1000,
  stageHeight: 700,
  headHeight: 29,
});
assert.equal(clampedEdges.top, 0);
assert.equal(clampedEdges.left, 880);

const clampedBottom = clampFloatingRect(
  { left: -900, top: 2000, width: 600, height: 400 },
  1000,
  700,
  29,
);
assert.equal(clampedBottom.left, -480);
assert.equal(clampedBottom.top, 671);

console.log('All Windows native sync, floating drag & zoom CSS checks passed.');
