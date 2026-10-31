import React from 'react';
import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ExpandableList } from './ExpandableList';

const items = Array.from({ length: 25 }, (_, i) => ({ deviceId: `d${i}`, alarm: i === 20 }));
const row = item => <div key={item.deviceId} data-testid="row">{item.deviceId}</div>;

describe('ExpandableList', () => {
  it('shows ten rows and a control for the rest', () => {
    render(<ExpandableList items={items} renderItem={row} noun="devices" />);
    expect(screen.getAllByTestId('row')).toHaveLength(10);
    expect(screen.getByRole('button', { name: 'Show 15 more devices' })).toBeTruthy();
  });

  it('expands on click and collapses on a click away, not on a click inside', () => {
    render(<div><span>elsewhere</span><ExpandableList items={items} renderItem={row} /></div>);
    fireEvent.click(screen.getByRole('button', { name: /Show 15 more/ }));
    expect(screen.getAllByTestId('row')).toHaveLength(25);
    fireEvent.mouseDown(screen.getAllByTestId('row')[3]);
    expect(screen.getAllByTestId('row')).toHaveLength(25);
    fireEvent.mouseDown(screen.getByText('elsewhere'));
    expect(screen.getAllByTestId('row')).toHaveLength(10);
  });

  it('always shows a pinned row (an alarm) beyond the limit without counting it', () => {
    render(<ExpandableList items={items} renderItem={row} isPinned={item => item.alarm} />);
    const ids = screen.getAllByTestId('row').map(r => r.textContent);
    expect(ids).toHaveLength(11);
    expect(ids).toContain('d20');
  });

  it('has no control for a short list, and shows everything when forced open', () => {
    const { unmount } = render(<ExpandableList items={items.slice(0, 10)} renderItem={row} />);
    expect(screen.queryByRole('button')).toBeNull();
    unmount();
    render(<ExpandableList items={items} renderItem={row} forceOpen />);
    expect(screen.getAllByTestId('row')).toHaveLength(25);
  });

  it('shows 25 on a wide screen and ten on a phone or tablet when given a base and a wide limit', () => {
    const many = Array.from({ length: 40 }, (_, i) => ({ deviceId: `w${i}` }));
    const original = window.matchMedia;
    try {
      window.matchMedia = query => ({ matches: true, media: query, addEventListener() {}, removeEventListener() {} });
      const wide = render(<ExpandableList items={many} renderItem={row} limit={{ base: 10, wide: 25 }} noun="tiles" />);
      expect(screen.getAllByTestId('row')).toHaveLength(25);
      expect(screen.getByRole('button', { name: 'Show 15 more tiles' })).toBeTruthy();
      wide.unmount();
      window.matchMedia = query => ({ matches: false, media: query, addEventListener() {}, removeEventListener() {} });
      render(<ExpandableList items={many} renderItem={row} limit={{ base: 10, wide: 25 }} noun="tiles" />);
      expect(screen.getAllByTestId('row')).toHaveLength(10);
      expect(screen.getByRole('button', { name: 'Show 30 more tiles' })).toBeTruthy();
    } finally {
      window.matchMedia = original;
    }
  });

  it('makes the control prominent: its own class, not the small ghost button', () => {
    render(<ExpandableList items={items} renderItem={row} />);
    const button = screen.getByRole('button', { name: /Show 15 more/ });
    expect(button.className).toBe('expandable-list-toggle');
  });
});
