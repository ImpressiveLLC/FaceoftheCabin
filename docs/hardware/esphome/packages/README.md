# Shared ESPHome packages for the QT Py sensor boxes (Boxes 2, 3, 4)

Include from a box file with `packages:`. Paths are relative to the box's YAML.

| Package | Hardware needed | Status |
|---|---|---|
| `ntp.yaml` | none | Included in `box2.yaml`. Validated with `esphome config`; **not flashed yet**. Adds wall-clock time and a "Time synced" diagnostic. |
| `pressure_dps310.yaml` | Adafruit DPS310 (STEMMA QT, product 4494, about $7) chained on the Qwiic bus | **Do not include** until the sensor is fitted. Adds "Barometric pressure" in hPa. Address 0x77, no clash with SEN66 (0x6B) or SHT45 (0x44). |

Boxes 3 and 4 have no firmware file yet (W-84). When they get one, start from `box2.yaml` and include the same packages.

**Gas slot: held.** DFRobot Gravity CO (SEN0466) is about $67 and O2 (SEN0465) about $85 per node (Mouser, tariff may apply). CO is already covered by the Kidde units. Buy none until a use is named. Check the part's I2C address against the bus before choosing a slot.

To check a change without flashing, copy the box file and `packages/` to a folder with a throwaway `secrets.yaml` (use a random base64 `api_key`; the all-zeros key is rejected) and run `esphome config box2.yaml`. On Windows keep the build path short; ESPHome's compile fails on paths near 250 characters.
