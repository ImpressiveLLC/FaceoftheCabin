package com.cabin.orchestrator.devices.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * category() formalizes groupings this enum's constants already carried as
 * plain Java comments (// Safety, // Security, etc.) -- this locks in that
 * the real, queryable field matches the exact same taxonomy, not a
 * reinterpretation of it.
 */
class DeviceTypeTest {

    @Test
    void everyDeviceTypeHasACategory() {
        for (DeviceType type : DeviceType.values()) {
            assertThat(type.category()).as("category for %s", type).isNotNull();
        }
    }

    @Test
    void categoriesMatchTheOriginalCommentGroupings() {
        assertThat(DeviceType.SMOKE_ALARM.category()).isEqualTo(DeviceCategory.SAFETY);
        assertThat(DeviceType.CO_ALARM.category()).isEqualTo(DeviceCategory.SAFETY);
        assertThat(DeviceType.WATER_LEAK_SENSOR.category()).isEqualTo(DeviceCategory.SAFETY);

        assertThat(DeviceType.CAMERA.category()).isEqualTo(DeviceCategory.SECURITY);
        assertThat(DeviceType.LOCK.category()).isEqualTo(DeviceCategory.SECURITY);
        assertThat(DeviceType.MOTION_SENSOR.category()).isEqualTo(DeviceCategory.SECURITY);
        assertThat(DeviceType.CONTACT_SENSOR.category()).isEqualTo(DeviceCategory.SECURITY);

        assertThat(DeviceType.THERMOSTAT.category()).isEqualTo(DeviceCategory.CLIMATE);
        assertThat(DeviceType.TEMPERATURE_SENSOR.category()).isEqualTo(DeviceCategory.CLIMATE);
        assertThat(DeviceType.HUMIDITY_SENSOR.category()).isEqualTo(DeviceCategory.CLIMATE);

        assertThat(DeviceType.WATER_PRESSURE_SENSOR.category()).isEqualTo(DeviceCategory.UTILITIES);
        assertThat(DeviceType.POWER_METER.category()).isEqualTo(DeviceCategory.UTILITIES);

        assertThat(DeviceType.DISHWASHER.category()).isEqualTo(DeviceCategory.APPLIANCES);
        assertThat(DeviceType.WASHING_MACHINE.category()).isEqualTo(DeviceCategory.APPLIANCES);
        assertThat(DeviceType.DRYER.category()).isEqualTo(DeviceCategory.APPLIANCES);

        assertThat(DeviceType.ROUTER.category()).isEqualTo(DeviceCategory.NETWORK);
        assertThat(DeviceType.UPS.category()).isEqualTo(DeviceCategory.NETWORK);

        assertThat(DeviceType.GOOGLE_HOME_DEVICE.category()).isEqualTo(DeviceCategory.PLATFORM);
        assertThat(DeviceType.HOME_ASSISTANT_ENTITY.category()).isEqualTo(DeviceCategory.PLATFORM);
        assertThat(DeviceType.DASHBOARD.category()).isEqualTo(DeviceCategory.PLATFORM);
    }
}
