import { describe, it, expect } from 'vitest';
import { electricalMonitoringDevices, electricalValue, monitoringDeviceTarget } from './electricalMonitoring';
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
  it('groups enabled services without enabling their parent and isolates locations', () => {
    const disabled={...parent('p'),attributes:{enabled:false,deviceLifecycle:'ASSIGNED'}};
    const service=child('power','p','power',12);
    const result = electricalMonitoringDevices([disabled,service]);
    expect(result).toHaveLength(1);
    expect(result[0].attributes.enabled).toBe(false);
    expect(result[0].attributes.monitoringGroup).toBe(true);
    expect(result[0].attributes.power).toBe(12);
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

describe('source identity and ambiguity', () => {
  it('groups a renamed SONOFF through the backend canonical identity without enabling its alias', () => {
    const original={deviceId:'z2m-0x1234567890abcdef',location:'cabin',type:'HOME_ASSISTANT_ENTITY',
      attributes:{enabled:false,deviceLifecycle:'ASSIGNED',smartSwitch:true}};
    const alias={...original,deviceId:'z2m-new-name',attributes:{enabled:false,candidate:true,
      smartSwitch:true,canonicalPhysicalId:original.deviceId,power:99}};
    const meter={deviceId:'ha-power',location:'cabin',type:'POWER_METER',
      attributes:{enabled:true,parentDeviceId:original.deviceId,entityId:'sensor.power',
      serviceRole:'measurement',sourceReportedFields:['power'],power:0}};
    const result=electricalMonitoringDevices([original,alias,meter]);
    expect(result[0].attributes.power).toBe(0);
    expect(result[0].attributes.energy).toBeUndefined();
    expect(result[0].attributes.enabled).toBe(false);
    expect(result[1]).toBe(alias);
  });
  it('keeps diagnostics and competing sources visible instead of choosing a value', () => {
    const p={deviceId:'p',location:'home',type:'POWER_METER',attributes:{enabled:true}};
    const child=(id,role='measurement')=>({deviceId:id,location:'home',type:'POWER_METER',
      attributes:{enabled:true,parentDeviceId:'p',entityId:'sensor.'+id,
        sourceReportedFields:['power'],serviceRole:role,power:10}});
    const result=electricalMonitoringDevices([p,child('a'),child('b'),child('diagnostic','diagnostic')]);
    expect(result).toHaveLength(4);
    expect(result[0].attributes.power).toBeUndefined();
  });
});

it('retains an orphan service in parent details without inventing kWh or a duplicate blank card', () => {
 const p={deviceId:'p',location:'cabin',type:'POWER_METER',attributes:{enabled:true,power:0}};
 const orphan={deviceId:'old-energy',location:'cabin',type:'POWER_METER',attributes:{
   enabled:true,parentDeviceId:'p',serviceRole:'unresolved',sourceReportedFields:[]}};
 const result=electricalMonitoringDevices([p,orphan]);
 expect(result).toHaveLength(1);
 expect(result[0].attributes.unresolvedServiceCount).toBe(1);
 expect(result[0].attributes.energy).toBeUndefined();
});

it('preserves temperature cards even if old backend metadata mistakenly marks a switch', () => {
 const temp={deviceId:'temp_kitchen',type:'TEMPERATURE_SENSOR',location:'cabin',
   attributes:{enabled:true,smartSwitch:true,temperature:21,voltage:3100}};
 expect(electricalMonitoringDevices([temp])[0]).toBe(temp);
});
it('resolves physical aliases but refuses cycles and cross-location navigation', () => {
 const p={deviceId:'parent',location:'cabin',attributes:{}};
 const a={deviceId:'alias',location:'cabin',attributes:{canonicalPhysicalId:'parent'}};
 const c={deviceId:'child',location:'cabin',attributes:{parentDeviceId:'alias'}};
 expect(monitoringDeviceTarget('child',[p,a,c])).toBe('parent');
 expect(monitoringDeviceTarget('child',[{...p,location:'home'},a,c])).toBe('alias');
 expect(monitoringDeviceTarget('child',[{...p,attributes:{parentDeviceId:'child'}},a,c])).toBe('child');
});


describe('relay status stays independent of power draw', () => {
  it.each(['ON', 'OFF'])('shows %s even when the attached load draws zero watts', state => {
    const device = parent('switch');
    device.attributes.state = state;
    const [card] = electricalMonitoringDevices([device]);
    expect(electricalValue(card.attributes)).toBe(`0 W \u00b7 48.32 kWh \u00b7 ${state}`);
  });
  it('does not infer off from zero watts or online connectivity', () => {
    const device = {...parent('switch'), state:'ONLINE'};
    const [card] = electricalMonitoringDevices([device]);
    expect(card.attributes.mappedSwitchState).toBeUndefined();
    expect(electricalValue(card.attributes)).toBe('0 W \u00b7 48.32 kWh');
  });
});
