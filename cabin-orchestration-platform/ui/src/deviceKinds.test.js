import { describe, expect, it } from 'vitest';
import { deviceKind, makeKindOf } from './deviceKinds';
import { duplicateOfZigbeeParent } from './zigbeeServices';

const dev = (deviceId, attributes = {}) => ({ deviceId, location: 'cabin', attributes });

describe('device kind', () => {
  it('splits battery, wired and service', () => {
    expect(deviceKind(dev('z2m-a', { powerSource: 'Battery' }))).toBe('battery');
    expect(deviceKind(dev('z2m-b', { battery: 80 }))).toBe('battery');
    expect(deviceKind(dev('z2m-c', { powerSource: 'Mains (single phase)' }))).toBe('wired');
    expect(deviceKind(dev('ha-x', { parentDeviceId: 'z2m-a' }))).toBe('service');
  });

  it('treats a bare Home Assistant root as data, and a root with children as the device it is', () => {
    const bare = dev('ha-sun', { discoveredFrom: 'Home Assistant' });
    const kidde = dev('ha-kidde', { discoveredFrom: 'Home Assistant' });
    const child = dev('ha-kidde-temp', { parentDeviceId: 'ha-kidde' });
    const kindOf = makeKindOf([bare, kidde, child]);
    expect(kindOf(bare)).toBe('service');
    expect(kindOf(kidde)).toBe('wired');
  });
});

describe('duplicateOfZigbeeParent', () => {
  const parent = dev('z2m-kitchen', { vendorReportedFields: ['temperature'] });
  const child = dev('ha-kitchen-t', { parentDeviceId: 'z2m-kitchen', entityId: 'sensor.k_t', device_class: 'temperature' });
  const byId = new Map([parent, child].map(d => [d.deviceId, d]));

  it('names the parent and measurement when the parent reports the same thing', () => {
    expect(duplicateOfZigbeeParent(child, byId)).toMatchObject({ parent, measurement: 'temperature' });
  });

  it('is null for a measurement the parent does not report', () => {
    const humidity = dev('ha-kitchen-h', { parentDeviceId: 'z2m-kitchen', entityId: 'sensor.k_h', device_class: 'humidity' });
    expect(duplicateOfZigbeeParent(humidity, new Map([...byId, [humidity.deviceId, humidity]]))).toBeNull();
  });
});
