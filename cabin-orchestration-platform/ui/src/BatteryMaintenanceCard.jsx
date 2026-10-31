import React from 'react';
import { ExpandableList } from './ExpandableList';
import { batteryMaintenanceRows, LOW_BATTERY_PERCENT } from './deviceKinds';

// A planned-maintenance view of the physical battery devices in use: lowest battery first, devices that have
// never reported a level last. Shown above the Device Manager list while the Kind filter is Battery devices.
export function BatteryMaintenanceCard({ devices, kindOf, onSelect }) {
  const rows = batteryMaintenanceRows(devices, kindOf);
  return (
    <section className="sidebar-card battery-maintenance" aria-label="Battery maintenance">
      <strong>Battery maintenance</strong>
      <p className="config-hint">
        Physical devices running on a battery, lowest first. Services and wired devices are not listed.
      </p>
      {rows.length === 0 && <p className="config-hint">No enabled battery devices.</p>}
      <ExpandableList items={rows} keyOf={row => row.deviceId} noun="battery devices"
        isPinned={row => row.low}
        renderItem={row => (
          <div key={row.deviceId} className="rule-row">
            <span className={`rule-dot ${row.low ? 'rule-inactive' : 'rule-defined'}`}>●</span>
            <div>
              <div className="rule-name">
                <button type="button" className="active-condition-link" onClick={() => onSelect?.(row.deviceId)}>
                  {row.area ? `${row.area} · ` : ''}{row.name}
                </button>
              </div>
              <div className="rule-detail">
                {row.level === null ? 'No battery level reported yet' : `${Math.round(row.level)}%`}
                {row.low && ` · at or below ${LOW_BATTERY_PERCENT}%, replace soon`}
                {row.lastSeen && ` · last heard ${new Date(row.lastSeen).toLocaleString()}`}
              </div>
            </div>
          </div>
        )} />
    </section>
  );
}
