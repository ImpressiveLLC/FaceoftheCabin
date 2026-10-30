import React, { useEffect, useRef, useState } from 'react';

// The common long-list approach: show a handful of rows, a control to show the rest, and collapse again when the
// person clicks anywhere outside the list. Rows that must never be hidden (an active alarm, the selected row)
// are always shown and do not count against the limit.
export const COLLAPSED_LIMIT = 10;

export function ExpandableList({ items, renderItem, keyOf = item => item.deviceId, isPinned = () => false,
                                  limit = COLLAPSED_LIMIT, forceOpen = false, noun = 'items' }) {
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
        <button type="button" className="btn-ghost btn-ghost-sm expandable-list-toggle" aria-expanded={open}
          onClick={() => setOpen(o => !o)}>
          {open ? 'Show fewer' : `Show ${hidden} more ${noun}`}
        </button>
      )}
    </div>
  );
}

function ExpandableRow({ item, renderItem }) {
  return renderItem(item);
}
