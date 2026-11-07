# ExcellentCrates V6 Converter (1.0.15)

Converts ExcellentCrates 6.6.1 data to the ExcellentCrates 7.2.0 format.

## Commands

- `/ecv6convert` or `/ecconvert` — add missing definitions.
- `/ecv6convert -f` — show force terms and clickable confirmation.
- `/ecv6convert -f confirm` — back up conflicting definitions, apply safe conversions, and report the result.

Permission: `excellentcratesv6converter.convert` (operators by default).

Item rewards use their first converted reward item as the preview icon. Command rewards use a command block when no custom preview icon is configured. Failed preview-item conversions use a barrier and are listed as failures.

## Console output

```text
[ExcellentCratesV6Converter] Manual recovery finished: <conversion counts>.
[Converter] SUCCESS: <definition converted>
[Converter] REVIEW: <setting needs review>
[Converter] FAILED: <definition and reason>
```

Full details are appended to `plugins/ExcellentCrates/conversion-report.txt`.

