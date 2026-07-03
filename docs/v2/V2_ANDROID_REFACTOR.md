# IdleFantasyktor V2 Android Refactor Plan

## Safety boundary

The Android app may expose read-only local telemetry. It must not expose generic
controller endpoints for starting sessions, adding queue entries, collecting
rewards, equipping items, buying, selling, or completing timers.

## Refactor order

1. Add characterization tests around current mechanics and session outcomes.
2. Extract pure shared mechanics:
   - toolEfficiency
   - tierIndex
   - reclaimChance
   - pet boosts
3. Move shared session lifecycle operations behind one domain service.
4. Replace duplicated queue starter branches with a single internal action
   registry while preserving exact current behavior.
5. Reduce repetitive JSON decoding by centralizing parsed player state in the
   repository/domain layer.
6. Remove duplicated ViewModel methods only after tests prove behavior parity.

## Do not do

- Do not change reward math during extraction.
- Do not merge an external automation dispatch endpoint into the normal app flow.
- Do not delete legacy queue code until behavior equivalence is tested.
