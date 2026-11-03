import React, { useEffect, useRef, useState } from 'react';

// The long-list approach for Devices: show a handful of rows, a control to show the rest, and collapse again when the
// person clicks anywhere outside the list. Rows that must never be hidden (an active alarm, the selected row)
// are always shown and do not count against the limit.
export const COLLAPSED_LIMIT = 10;
// Nate, 2026-11-02: only Devices limits how many cards it shows. Everywhere else shows every item unless a caller passes a limit.
// A wide screen (a conventional monitor or a kiosk) has room for about three full rows of cards; phones and tablets keep ten.
export const WIDE_QUERY = '(min-width: 1200px)';

function matchWide() {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(WIDE_QUERY) : null;
}

function useWide() {
  const [wide, setWide] = useState(() => Boolean(matchWide()?.matches));
  useEffect(() => {
    const query = matchWide();
    if (!query || !query.addEventListener) return undefined;
    const onChange = event => setWide(event.matches);
    setWide(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return wide;
}

export function ExpandableList({ items, renderItem, keyOf = item => item.deviceId, isPinned = () => false,
                                  limit: limitProp = Infinity, forceOpen = false, noun = 'items' }) {
  // limit is a number (default: none), or { base, wide } to show more on a wide screen.
  const wide = useWide();
  const limit = typeof limitProp === 'number' ? limitProp : (wide ? limitProp.wide : limitProp.base);
  const [open, setOpen] = useState(false);
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const clickAway = event => {
      if (ref.current && !ref.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener('mousedown', clickAway);
    return () => document.removeEventListener('mousedown', clickAway);
  }, [open]);

  const needsToggle = items.length > limit;
  const showAll = forceOpen || open || !needsToggle;
  let room = limit;
  const visible = showAll ? items : items.filter(item => {
    if (isPinned(item)) return true;
    if (room <= 0) return false;
    room -= 1;
    return true;
  });
  const hidden = items.length - visible.length;

  return (
    <div className="expandable-list" ref={ref}>
      {visible.map(item => <ExpandableRow key={keyOf(item)} item={item} renderItem={renderItem} />)}
      {needsToggle && !forceOpen && (
        <button type="button" className="expandable-list-toggle" aria-expanded={open}
          onClick={() => setOpen(o => !o)}>
          {open ? 'Show fewer' : `Show ${hidden} more ${noun}`}
          <span aria-hidden="true" className="expandable-list-chevron">{open ? '▲' : '▼'}</span>
        </button>
      )}
    </div>
  );
}

function ExpandableRow({ item, renderItem }) {
  return renderItem(item);
}
