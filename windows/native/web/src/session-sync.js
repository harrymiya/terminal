/**
 * Pure session-sync reconciliation helpers shared by the Windows WebView UI
 * and Node automated tests.
 */

export function normalizeSessionId(id) {
  return String(id ?? '').replaceAll('-', '').toLowerCase();
}

export function isValidSessionId(id) {
  return /^[0-9a-f]{32}$/.test(normalizeSessionId(id));
}

export function formatSessionTitle({ workspace, agent, title }) {
  const workspaceName = String(workspace?.name || workspace?.path || '');
  const agentName = String(agent?.name || agent?.command || '');
  const current = String(title || '');
  if (!workspaceName) return current || agentName;
  if (!agentName) return current || workspaceName;
  const legacyDefault = `${workspaceName} · ${agentName}`;
  return !current || current === legacyDefault ? `${agentName} · ${workspaceName}` : current;
}

export function buildSessionSyncItem({ id, workspace, agent, title }) {
  const normalizedId = normalizeSessionId(id);
  const directoryPath = String(workspace?.path ?? '');
  const directoryName = String(workspace?.name || directoryPath);
  const agentCommand = String(agent?.command ?? '');
  const agentName = String(agent?.name || agentCommand);
  const displayName = formatSessionTitle({ workspace, agent, title });
  return {
    id: normalizedId,
    directory_path: directoryPath,
    directory_name: directoryName,
    agent_name: agentName,
    agent_command: agentCommand,
    display_name: displayName,
  };
}

export function findExistingSession(sessions, { id = '', hostId = '', directoryPath = '', agentCommand = '' }) {
  const normalizedId = normalizeSessionId(id);
  const list = [...sessions];
  if (normalizedId) {
    const byId = list.find(
      (session) =>
        normalizeSessionId(session.id) === normalizedId ||
        normalizeSessionId(session.remoteId) === normalizedId,
    );
    if (byId) return byId;
  }
  if (hostId && directoryPath && agentCommand) {
    return (
      list.find(
        (session) =>
          session.host?.id === hostId &&
          session.workspace?.path === directoryPath &&
          session.agent?.command === agentCommand,
      ) || null
    );
  }
  return null;
}

export function mergeSessionSyncItems(remoteItems, mutations = []) {
  const merged = new Map();
  for (const item of remoteItems || []) {
    const id = normalizeSessionId(item?.id);
    if (id) merged.set(id, { ...item, id });
  }
  for (const mutation of mutations || []) {
    const id = normalizeSessionId(mutation?.session_id);
    if (!id) continue;
    if (mutation.method === 'DELETE') {
      merged.delete(id);
    } else if (mutation.method === 'PUT' && mutation.item) {
      merged.set(id, { ...mutation.item, id });
    }
  }
  const byWorkspace = new Map();
  for (const item of merged.values()) {
    const key =
      item.directory_path && item.agent_command
        ? `ws:${item.directory_path}\0${item.agent_command}`
        : `id:${item.id}`;
    if (byWorkspace.has(key)) byWorkspace.delete(key);
    byWorkspace.set(key, item);
  }
  return [...byWorkspace.values()];
}

export function planSyncedSessionChanges({
  currentSessions,
  hosts,
  syncedHosts,
  remappedHosts = {},
  now = Date.now(),
  gracePeriodMs = 15_000,
  maxSessions = 8,
}) {
  const hostsById = new Map((hosts || []).map((host) => [host.id, host]));
  const syncedEntries = Object.entries(syncedHosts || {});
  const syncedByHost = new Map(
    syncedEntries.map(([hostId, items]) => [hostId, Array.isArray(items) ? items : []]),
  );

  const hostUpdates = [];
  const renames = [];
  const toClose = [];
  const remainingSessions = [];

  for (const session of currentSessions) {
    const currentHostId = session.host?.id || '';
    const effectiveHostId = remappedHosts[currentHostId] || currentHostId;
    const latestHost = hostsById.get(effectiveHostId);

    if (!latestHost) {
      if (now - (session.startedAt || 0) > gracePeriodMs) {
        toClose.push(session.id);
        continue;
      }
      remainingSessions.push(session);
      continue;
    }

    if (
      latestHost.id !== currentHostId ||
      JSON.stringify(latestHost) !== JSON.stringify(session.host)
    ) {
      hostUpdates.push({ sessionId: session.id, host: { ...latestHost } });
    }

    if (!syncedByHost.has(effectiveHostId)) {
      remainingSessions.push({ ...session, host: { ...latestHost } });
      continue;
    }

    const items = syncedByHost.get(effectiveHostId);
    const remoteIds = new Set(items.map((item) => normalizeSessionId(item?.id)).filter(Boolean));
    const sessionRemoteId = normalizeSessionId(session.remoteId || session.id);
    const matched =
      items.find((item) => normalizeSessionId(item?.id) === sessionRemoteId) ||
      items.find((item) => normalizeSessionId(item?.id) === normalizeSessionId(session.id)) ||
      items.find(
        (item) =>
          item?.directory_path === session.workspace?.path &&
          item?.agent_command === session.agent?.command,
      );

    if (matched) {
      const nextRemoteId = normalizeSessionId(matched.id) || sessionRemoteId;
      const nextTitle = formatSessionTitle({
        workspace: session.workspace,
        agent: session.agent,
        title: matched.display_name || session.title,
      });
      if (nextRemoteId !== sessionRemoteId || (matched.display_name && matched.display_name !== session.title)) {
        renames.push({ sessionId: session.id, remoteId: nextRemoteId, title: nextTitle });
      }
      remainingSessions.push({
        ...session,
        remoteId: nextRemoteId,
        title: nextTitle,
        host: { ...latestHost },
      });
    } else if (!remoteIds.has(sessionRemoteId) && now - (session.startedAt || 0) > gracePeriodMs) {
      toClose.push(session.id);
    } else {
      remainingSessions.push({ ...session, host: { ...latestHost } });
    }
  }

  const toOpen = [];
  for (const [hostId, items] of syncedByHost.entries()) {
    const host = hostsById.get(hostId);
    if (!host) continue;
    for (const item of items) {
      if (remainingSessions.length + toOpen.length >= maxSessions) break;
      if (!isValidSessionId(item?.id) || !item?.directory_path || !item?.agent_command) continue;
      const existing = findExistingSession([...remainingSessions, ...toOpen.map((entry) => ({
        id: normalizeSessionId(entry.item.id),
        remoteId: normalizeSessionId(entry.item.id),
        host: entry.host,
        workspace: { path: entry.item.directory_path },
        agent: { command: entry.item.agent_command },
      }))], {
        id: item.id,
        hostId: host.id,
        directoryPath: item.directory_path,
        agentCommand: item.agent_command,
      });
      if (!existing) {
        toOpen.push({ host, item: { ...item, id: normalizeSessionId(item.id) } });
      }
    }
  }

  return { hostUpdates, renames, toClose, toOpen };
}
