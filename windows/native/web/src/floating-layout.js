/**
 * Geometry helpers for movable, resizable floating terminal cards.
 */

export function clampFloatingRect(rect, stageWidth, stageHeight, headHeight = 29) {
  const width = Math.max(240, Math.round(Number(rect?.width) || 480));
  const height = Math.max(140, Math.round(Number(rect?.height) || 320));
  let left = Math.round(Number(rect?.left) || 0);
  let top = Math.round(Number(rect?.top) || 0);

  if (stageWidth > 0) {
    const keepX = Math.min(width, stageWidth, 120);
    const minLeft = keepX - width;
    const maxLeft = stageWidth - keepX;
    left = Math.max(minLeft, Math.min(maxLeft, left));
  }
  if (stageHeight > 0) {
    const minHead = Math.min(stageHeight, Math.max(24, Math.round(headHeight) || 29));
    const maxTop = Math.max(0, stageHeight - minHead);
    top = Math.max(0, Math.min(maxTop, top));
  }
  return { left, top, width, height };
}

export function defaultFloatingRect(stageWidth, stageHeight, floatingIndex = 0) {
  const width = Math.max(240, Math.round(stageWidth * 0.7));
  const height = Math.max(140, Math.round(stageHeight * 0.7));
  const offset = 24 * Math.max(0, floatingIndex);
  return clampFloatingRect(
    {
      left: Math.round(stageWidth * 0.12) + offset,
      top: Math.round(stageHeight * 0.12) + offset,
      width,
      height,
    },
    stageWidth,
    stageHeight,
  );
}

export function computeDraggedFloatingRect({
  originLeft,
  originTop,
  deltaX,
  deltaY,
  width,
  height,
  stageWidth,
  stageHeight,
  headHeight = 29,
}) {
  return clampFloatingRect(
    {
      left: originLeft + deltaX,
      top: originTop + deltaY,
      width,
      height,
    },
    stageWidth,
    stageHeight,
    headHeight,
  );
}
