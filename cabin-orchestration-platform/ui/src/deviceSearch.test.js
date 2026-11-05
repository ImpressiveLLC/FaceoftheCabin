import { describe, expect, it } from "vitest";
import { buildDeviceIndex, matchesDeviceQuery, parseDeviceQuery, searchDevices } from "./deviceSearch";

const devices = [
  { deviceId: "z2m-temp_mech_room", name: "Mech room temp", type: "TEMPERATURE_SENSOR", state: "ONLINE", location: "cabin",
    attributes: { room: "Mechanical", battery: 18, linkquality: 92, vendor: "Sonoff", powerSource: "battery" } },
  { deviceId: "z2m-plug-kitchen", name: "Kitchen plug", type: "SMART_PLUG", state: "OFFLINE", location: "cabin",
    attributes: { room: "Kitchen", state: "ON", rssi: -75, extra: { nested: { firmware: "1.2.3" } } } },
  { deviceId: "ha-kidde-co", name: "Kidde CO alarm", type: "HOME_ASSISTANT_ENTITY", state: "ONLINE", location: "cabin",
    attributes: { parentDeviceId: "ha-kidde", discoveredFrom: "Home Assistant" } },
  { deviceId: "ha-kidde", name: "Kidde Air Quality Alarm", type: "CO_ALARM", state: "ALARM", location: "home", attributes: { deviceLifecycle: "ignored" } },
];
const ids = (q, ctx) => searchDevices(devices, q, ctx).map(d => d.deviceId);

describe("device search", () => {
  it("empty query returns everything", () => {
    expect(ids("")).toHaveLength(4);
    expect(parseDeviceQuery("   ").empty).toBe(true);
  });
  it("matches any data element, case-insensitively, across separators", () => {
    expect(ids("MECH ROOM")).toEqual(["z2m-temp_mech_room"]);
    expect(ids("sonoff")).toEqual(["z2m-temp_mech_room"]);
    expect(ids("1.2.3")).toEqual(["z2m-plug-kitchen"]);
    expect(ids("linkquality")).toEqual(["z2m-temp_mech_room"]);
  });
  it("ANDs words, ORs alternatives, excludes with - or !", () => {
    expect(ids("kitchen plug")).toEqual(["z2m-plug-kitchen"]);
    expect(ids("kitchen mech")).toEqual([]);
    expect(ids("kitchen OR mech")).toHaveLength(2);
    expect(ids("kitchen | mech")).toHaveLength(2);
    expect(ids("z2m -kitchen")).toEqual(["z2m-temp_mech_room"]);
    expect(ids("z2m !kitchen")).toEqual(["z2m-temp_mech_room"]);
  });
  it("supports quoted phrases and wildcards", () => {
    expect(ids('"mech room"')).toEqual(["z2m-temp_mech_room"]);
    expect(ids('"room mech"')).toEqual([]);
    expect(ids("z2m-*kitchen")).toEqual(["z2m-plug-kitchen"]);
  });
  it("restricts to a field, by last path segment or alias", () => {
    expect(ids("room:kitchen")).toEqual(["z2m-plug-kitchen"]);
    expect(ids("state:offline")).toEqual(["z2m-plug-kitchen"]);
    expect(ids("type:sensor")).toEqual(["z2m-temp_mech_room"]);
    expect(ids("id:kidde")).toEqual(["ha-kidde-co", "ha-kidde"]);
    expect(ids("room:mech -room:kitchen")).toEqual(["z2m-temp_mech_room"]);
  });
  it("compares numbers", () => {
    expect(ids("battery:<20")).toEqual(["z2m-temp_mech_room"]);
    expect(ids("battery:>=19")).toEqual([]);
    expect(ids("rssi:<-70")).toEqual(["z2m-plug-kitchen"]);
    expect(ids("rssi:>-70")).toEqual([]);
  });
  it("finds derived fields: parent, service, workflow, kind, lifecycle", () => {
    const workflows = [{ name: "Kitchen freeze guard", triggerDeviceId: "z2m-plug-kitchen", actions: [] }];
    const ctx = { workflows, kindOf: d => (d.attributes?.parentDeviceId ? "service" : "wired"), lifecycleOf: d => d.attributes?.deviceLifecycle || "assigned" };
    expect(ids("parent:air quality", ctx)).toEqual(["ha-kidde-co"]);
    expect(ids("service:ha-kidde-co", ctx)).toEqual(["ha-kidde"]);
    expect(ids("workflow:freeze", ctx)).toEqual(["z2m-plug-kitchen"]);
    expect(ids("kind:service", ctx)).toEqual(["ha-kidde-co"]);
    expect(ids("lifecycle:ignored", ctx)).toEqual(["ha-kidde"]);
  });
  it("matchesDeviceQuery tolerates a device with no index entries", () => {
    expect(matchesDeviceQuery(parseDeviceQuery("x"), undefined)).toBe(false);
    expect(buildDeviceIndex([]).size).toBe(0);
  });
});
