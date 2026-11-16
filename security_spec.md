# Security Specification & Payload-First TDD

## 1. Data Invariants
1. **Verified Authentication Gate**: All reads and writes across every collection strictly require `request.auth != null && request.auth.token.email_verified == true`. Unverified email accounts cannot read or mutate operational data.
2. **PII Isolation**: Private user emails reside exclusively in `/users/{userId}` and are readable only by the document owner or a verified manager. Operational `/staffDirectory/{userId}` omits PII (`email`, `phone`, `address`).
3. **No Self-Assigned Manager Privilege**: Non-admin users creating `/users/{userId}` or `/staffDirectory/{userId}` must have `role == 'staff'` unless they match the bootstrapped verified manager email (`yatheili713@gmail.com`). Regular users cannot write to `/managers/{userId}` or modify their own `role`.
4. **Relational Integrity on Schedule Changes**: Every `/scheduleChanges/{changeId}` creation verifies that the referenced `/shifts/{shiftId}` exists (`exists(...)`), sets `requesterUid == request.auth.uid`, and starts with `status == 'pending'`.
5. **Terminal State Locking**: Once a `/scheduleChanges/{changeId}` reaches `'approved'` or `'denied'`, subsequent updates are blocked.
6. **Temporal Integrity**: `createdAt` and `updatedAt` must equal `request.time` on create, and `updatedAt == request.time` with immutable `createdAt` on update.
7. **Strict Key & Size Limits**: Every write uses `isValid[Entity]()` checking `hasAll`, `hasOnly`, data types, regex ID/date/time patterns, and bounded string/array lengths.

## 2. The "Dirty Dozen" Adversarial Payloads
1. **Unverified Email Bypass**: Creating a shift report while `request.auth.token.email_verified == false` -> `PERMISSION_DENIED`.
2. **Self-Promotion Privilege Escalation**: Creating `/users/attacker` with `role: "manager"` by non-admin -> `PERMISSION_DENIED`.
3. **Ghost Field Shadow Write**: Updating `/shifts/s1` with an extra shadow key `isAdminOverride: true` -> `PERMISSION_DENIED`.
4. **Cross-User PII Scraping**: Calling `get(/users/victimUid)` as a non-manager staff user -> `PERMISSION_DENIED`.
5. **Orphaned Schedule Change**: Creating `/scheduleChanges/c1` referencing a non-existent `shiftId: "ghost_shift"` -> `PERMISSION_DENIED`.
6. **Requester Spoofing**: Creating `/scheduleChanges/c2` with `requesterUid: "otherStaffUid"` -> `PERMISSION_DENIED`.
7. **Status Shortcutting on Create**: Creating `/scheduleChanges/c3` with `status: "approved"` directly -> `PERMISSION_DENIED`.
8. **Terminal State Tampering**: Updating `/scheduleChanges/c1` after `status` is already `'approved'` -> `PERMISSION_DENIED`.
9. **Staff Approving Own Request**: Staff user updating `status` from `'pending'` to `'approved'` on `/scheduleChanges/c1` -> `PERMISSION_DENIED`.
10. **ID Poisoning**: Creating `/shifts/invalid$id!with*badchars_and_1500_bytes` -> `PERMISSION_DENIED`.
11. **Timestamp Forgery**: Creating `/shiftReports/r1` with client-forged `createdAt` not equal to `request.time` -> `PERMISSION_DENIED`.
12. **Unbounded Array Injection**: Creating `/shifts/s2` with 25 entries in `candidateUids` (limit 20) -> `PERMISSION_DENIED`.
