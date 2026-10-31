import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { BatteryMaintenanceCard } from './BatteryMaintenanceCard';
import { batteryMaintenanceRows, makeKindOf } from './deviceKinds';

const dev = (deviceId, attributes, name = deviceId) => ({ deviceId, name, location: 'cabin', lastSeen: '2026-10-30T12:00:00Z', attributes });

const devices = [
  dev('z2m-ok', { enabled: true, powerSource: 'Battery', battery: 90, area: 'Kitchen' }, 'Kitchen sensor'),
  dev('z2m-low', { enabled: true, powerSource: 'Battery', battery: 12 }, 'Attic sensor'),
  dev('z2m-unknown', { enabled: true, powerSource: 'Battery' }, 'New sensor'),
  dev('z2m-off', { enabled: false, powerSource: 'Battery', battery: 5 }, 'Disabled sensor'),
  dev('z2m-mains', { enabled: true, powerSource: 'Mains (single phase)' }, 'Heater plug'),
  dev('ha-child', { enabled: true, parentDeviceId: 'z2m-low', battery: 12 }, 'Attic battery service'),
];

describe('batteryMaintenanceRows', () => {
  it('lists only enabled physical battery devices, lowest first, unreported last', () => {
    const rows = batteryMaintenanceRows(devices, makeKindOf(devices));
    expect(rows.map(r => r.deviceId)).toEqual(['z2m-low', 'z2m-ok', 'z2m-unknown']);
    expect(rows[0].low).toBe(true);
    expect(rows[2].level).toBeNull();
  });
});

describe('battery level from the service', () => {
  it('uses the Home Assistant battery service until the Zigbee device reports', () => {
    const list = [
      dev('z2m-door', { enabled: true, powerSource: 'Battery' }, 'Door'),
      dev('ha-door-battery', { enabled: true, parentDeviceId: 'z2m-door', battery: 67 }, 'Door battery'),
    ];
    const rows = batteryMaintenanceRows(list, makeKindOf(list));
    expect(rows).toHaveLength(1);
    expect(rows[0].level).toBe(67);
  });
});

describe('BatteryMaintenanceCard', () => {
  it('shows each device with its level and opens one on click', () => {
    const onSelect = vi.fn();
    render(<BatteryMaintenanceCard devices={devices} kindOf={makeKindOf(devices)} onSelect={onSelect} />);
    expect(screen.getByText(/12%/)).toBeTruthy();
    expect(screen.getByText(/replace soon/)).toBeTruthy();
    expect(screen.getByText(/No battery level reported yet/)).toBeTruthy();
    expect(screen.queryByText('Heater plug')).toBeNull();
    fireEvent.click(screen.getByText('Kitchen · Kitchen sensor'));
    expect(onSelect).toHaveBeenCalledWith('z2m-ok');
    cleanup();
  });
});
