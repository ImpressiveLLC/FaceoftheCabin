import { describe, expect, it } from 'vitest';
import { collapseDuplicateZigbeeServices } from './zigbeeServices';

const parent = (extra = {}) => ({
  deviceId: 'z2m-temp_outside_lowest', name: 'temp_outside_lowest', type: 'TEMPERATURE_SENSOR', location: 'cabin',
  attributes: { enabled: true, vendorReportedFields: ['battery', 'temperature'], ...extra },
});
const entity = (id, entityId, extra = {}, type = 'TEMPERATURE_SENSOR') => ({
  deviceId: id, name: id, type, location: 'cabin',
  attributes: { entityId, parentDeviceId: 'z2m-temp_outside_lowest', enabled: true, ...extra },
});

describe('collapseDuplicateZigbeeServices (D13 A1)', () => {
  it('shows one card: the entity repeating a reading its Zigbee parent reports is hidden', () => {
    const out = collapseDuplicateZigbeeServices([
      parent(),
      entity('ha-cabin-sensor-temp-outside-lowest-temperature', 'sensor.temp_outside_lowest_temperature', { device_class: 'temperature', temperature: 14.1 }),
    ]);
    expect(out.map(d => d.deviceId)).toEqual(['z2m-temp_outside_lowest']);
  });

  it("fills the parent's blank reading from the hidden entity after a restart, never overwriting a real one", () => {
    const blank = collapseDuplicateZigbeeServices([
      parent(),
      entity('ha-t', 'sensor.t', { device_class: 'temperature', temperature: 14.1 }),
    ]);
    expect(blank[0].attributes.temperature).toBe(14.1);
    const real = collapseDuplicateZigbeeServices([
      parent({ temperature: 15.2 }),
      entity('ha-t', 'sensor.t', { device_class: 'temperature', temperature: 14.1 }),
    ]);
    expect(real[0].attributes.temperature).toBe(15.2);
  });

  it('keeps a derived entity the parent does not report, a switch, and anything under a disabled parent', () => {
    const derived = entity('ha-dew', 'sensor.dew_point', { unit_of_measurement: 'C' }, 'HOME_ASSISTANT_ENTITY');
    const humidity = entity('ha-h', 'sensor.h', { device_class: 'humidity' }, 'HUMIDITY_SENSOR'); // parent reports no humidity
    const sw = entity('ha-sw', 'switch.x', { device_class: 'temperature' });
    expect(collapseDuplicateZigbeeServices([parent(), derived, humidity, sw]).map(d => d.deviceId))
      .toEqual(['z2m-temp_outside_lowest', 'ha-dew', 'ha-h', 'ha-sw']);
    expect(collapseDuplicateZigbeeServices([
      parent({ enabled: false }),
      entity('ha-t', 'sensor.t', { device_class: 'temperature' }),
    ]).map(d => d.deviceId)).toEqual(['z2m-temp_outside_lowest', 'ha-t']);
  });

  it('leaves electrical measurements to electricalMonitoring and other locations alone', () => {
    const power = entity('ha-p', 'sensor.p', { device_class: 'power', power: 3 }, 'POWER_METER');
    const elsewhere = { ...entity('ha-home', 'sensor.home_t', { device_class: 'temperature' }), location: 'home' };
    expect(collapseDuplicateZigbeeServices([parent({ vendorReportedFields: ['power', 'temperature'] }), power, elsewhere])
      .map(d => d.deviceId)).toEqual(['z2m-temp_outside_lowest', 'ha-p', 'ha-home']);
  });
});
