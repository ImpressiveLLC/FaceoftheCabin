import { describe, it, expect } from 'vitest';
import { electricalMonitoringDevices } from './electricalMonitoring';
const parent = (id, location = 'cabin', energy = 48.32) => ({deviceId:id, name:id, type:'POWER_METER', location, attributes:{enabled:true, deviceLifecycle:'ASSIGNED', vendorReportedFields:['power','voltage', ...(energy == null ? [] : ['energy'])], power:0, voltage:122.3, ...(energy == null ? {} : {energy})}});
const child = (id, pid, field, value, extra = {}) => ({deviceId:id, name:id, type:'POWER_METER', location:'cabin', attributes:{enabled:true, deviceLifecycle:'ASSIGNED', parentDeviceId:pid, entityId:`sensor.${id}`, sourceReportedFields:[field], [field]:value, ...extra}});
describe('one physical smart-switch card', () => {
  it('keeps heater, breaker and differently named same-model plugs at both locations; removes duplicate source services', () => {
    const heater=parent('heater'), breaker=parent('breaker'), other=parent('arbitrary','home');
    const result=electricalMonitoringDevices([heater,breaker,other,child('heater_power','heater','power',0)]);
    expect(result.map(d=>d.deviceId)).toEqual(['heater','breaker','arbitrary']);
    expect(result[0].attributes.power).toBe(0);
    expect(result[0].attributes.energy).toBe(48.32);
  });
  it('never adds energy for the SONOFF W/V/A-only source or enables candidates', () => {
    const sonoff=parent('sonoff','cabin',null);
    const disabled={...parent('disabled'),attributes:{enabled:false,deviceLifecycle:'ASSIGNED'}};
    const candidate={...parent('candidate'),attributes:{enabled:false,deviceLifecycle:'CANDIDATE'}};
    const result=electricalMonitoringDevices([sonoff,disabled,candidate]);
    expect(result[0].attributes.energy).toBeUndefined();
    expect(result[1]).toBe(disabled);
    expect(result[2]).toBe(candidate);
  });
  it('does not swallow an enabled child of a disabled parent or a service at another location', () => {
    const disabled={...parent('p'),attributes:{enabled:false,deviceLifecycle:'ASSIGNED'}};
    const service=child('power','p','power',12);
    expect(electricalMonitoringDevices([disabled,service])).toEqual([disabled,service]);
    expect(electricalMonitoringDevices([parent('p','home'),service])).toHaveLength(2);
  });
  it('uses existing unambiguous switch state, never a relay command or configuration threshold', () => {
    const switchService={...child('relay','p','state','ON'),type:'HOME_ASSISTANT_ENTITY',attributes:{enabled:true,parentDeviceId:'p',entityId:'switch.relay',state:'ON'}};
    const result=electricalMonitoringDevices([parent('p'),switchService]);
    expect(result[0].attributes.mappedSwitchState).toBe('ON');
    expect(switchService.attributes).not.toHaveProperty('mappedSwitchState');
    const conflicting={...switchService,deviceId:'other',attributes:{...switchService.attributes,state:'OFF'}};
    expect(electricalMonitoringDevices([parent('p'),switchService,conflicting])[0].attributes.mappedSwitchState).toBeUndefined();
  });
});
