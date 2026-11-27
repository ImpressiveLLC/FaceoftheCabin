# W-168 B1 primary-source verification

Reviewed 2026-11-27 against the linked editions below. Authority: #244 comments 7225748072, 7227353497 and 7227801981. This is evidence for peer review, not approval of ontology rows, thresholds, commissioning or a health assessment.

## Sensor measurements

[Sensirion SEN6x datasheet, version 1.0, October 2026](https://sensirion.com/media/documents/FAFC548D/6AC50BC5/PS_DS_SEN6x.pdf), page 1, distinguishes SEN66's PM, RH, temperature, VOC, NOx and CO2 channels from the HCHO-capable variants. Section 1.4, page 7, specifies VOC and NOx Index outputs of 1 to 500 index points. They are not concentration measurements or US AQI.

Section 4.8.7, page 30, is the SEN66-specific measured-value command. PM values use a factor of ten and micrograms per cubic metre; VOC/NOx indexes also use a factor of ten. Unknown PM and CO2 values use `0xFFFF`; unknown signed temperature, humidity and index values use `0x7FFF`. NOx may be unavailable during startup. Decode unavailable values before numeric scaling or display. Do not copy another SEN6x variant's signedness or startup behavior. This protocol description does not establish which channels the installed firmware currently publishes.

## Humidity candidates

[WMO-No. 8, seventh edition, 2008](https://www.weather.gov/media/epz/mesonet/CWOP-WMO8.pdf), Annex 4.B, printed page I.4-29 (PDF page 119), supports the water approximation with constants 6.112, 17.62 and 243.12 and domain -45 to 60 degrees Celsius. The source separately describes ice and pressure-enhancement terms. Our candidate deliberately omits pressure enhancement and makes no frost-point claim; calculated dew point must also stay inside the chosen approximation domain.

[Vaisala absolute humidity](https://docs.vaisala.com/r/M211280EN-E/en-US/GUID-6454B70E-2FB0-437D-8D3E-0DA8A5AD35D2) supplies the ideal-gas expression `216.679 * Pw / (T + 273.15)` in g/m3. Its [water vapor pressure definition](https://docs.vaisala.com/r/M211280EN-E/en-US/GUID-718156BA-B884-494E-9739-D102F69AE5FB) supplies `Pw = RH * Pws / 100` in hPa. This verifies the draft's units and expression, not the performance of a calibrated instrument or an implemented formula.

## AQI method boundary

[AirNow technical assistance document, May 2026](https://document.airnow.gov/technical-assistance-document-for-the-reporting-of-daily-air-quailty.pdf), Section IV, printed pages 13-14, contains Equation 1 and Table 6. These are not in an appendix. PM2.5 concentration is truncated to one decimal place before interpolation; the resulting index is rounded to an integer. The table's PM2.5 input is a 24-hour concentration. The document treats NowCast separately; an instantaneous sensor sample is not automatically either method. Its above-500 discussion retains the upper linear slope rather than silently clamping to 500.

No breakpoint table or policy threshold is adopted here. Before implementation, select a named method/revision with complete averaging, coverage, rounding, missing-data and out-of-scale behavior. Device VOC/NOx indexes cannot substitute for pollutant concentration in that method.

## Remaining live evidence and implementation gates

- Kidde TOTAL VOC: confirm stable entity/device ID, source `device_class` and `unit_of_measurement` before adding a unit. An unlabelled number or device name alone is insufficient. Metadata requested on #244 comment 7227537693.
- SEN66: obtain the installed Box 3 channel names, units, timestamps and stable parent binding. Public T/RH tiles do not prove PM or index publishing. The smallest proposed UI slice is a source-typed PM2.5 concentration tile and distinct VOC/NOx index tiles, preserving freshness and quality; no AQI conversion or implicit new enrollment.
- Current HA discovery retains source metadata, while the generic `AIR_QUALITY_SENSOR` tile displays `airQualityIndex` without a unit. A label repair must preserve the actual measurement identity and existing history; it must not legitimize a VOC-to-AQI semantic conflation.
- Code owns intraday telemetry-history and CSV work under 7227801981. This evidence record does not change that route, its authorization, bucket logic or UI controls.
- Source review is distinct from peer acceptance and live applicability. No B1 runtime rows or numeric thresholds are released by this document.
