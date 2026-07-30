const fs = require("fs");
const EXPECTED_MONGO_IMAGE =
  "mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a";

function readJson(path) {
  return JSON.parse(fs.readFileSync(path, "utf8").trim());
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function relationByCollection(report, collection) {
  const relation = report.findings.ownershipRelations.find(
    (entry) => entry.sourceCollection === collection
  );
  assert(relation, `Missing relation for ${collection}`);
  return relation;
}

function referenceByField(report, field) {
  const reference = report.findings.defaultTemplateReferences.find(
    (entry) => entry.referenceField === field
  );
  assert(reference, `Missing default reference for ${field}`);
  return reference;
}

function assertRelation(report, collection, expected) {
  const relation = relationByCollection(report, collection);
  Object.entries(expected.counts).forEach(([key, value]) => {
    assert(
      relation.counts[key] === value,
      `${collection}.${key}: expected ${value}, got ${relation.counts[key]}`
    );
  });
  assert(
    relation.scannedDocuments === expected.scannedDocuments,
    `${collection}.scannedDocuments mismatch`
  );
  assert(
    relation.distinctMissingOwners === expected.distinctMissingOwners,
    `${collection}.distinctMissingOwners mismatch`
  );
  assert(
    relation.candidateLogicalBsonBytes > 0,
    `${collection}.candidateLogicalBsonBytes must be positive`
  );
}

function verifyCompleteReport(
  report,
  expectedCommitSha,
  expectedHarnessSha
) {
  assert(
    report.schemaVersion === "didimlog-orphan-dry-run/v1",
    "Unexpected schema version"
  );
  assert(report.mode === "READ_ONLY_DRY_RUN", "Unexpected mode");
  assert(report.completed === true, "Report must be complete");
  assert(report.status === "COMPLETE", "Unexpected status");
  assert(report.decision === "REVIEW_REQUIRED", "Unexpected decision");
  assert(
    report.database.name === "didimlog-orphan-fixture",
    "Unexpected database"
  );
  assert(
    report.database.mongoVersion.startsWith("7.0."),
    "Unexpected MongoDB version"
  );
  assert(
    report.database.targetScope === "local-fixture",
    "Unexpected target scope"
  );
  assert(
    report.database.configuredReadPreference === "secondaryPreferred",
    "Configured read preference was not recorded"
  );
  assert(
    report.database.effectiveReadPreference === "primary" &&
      report.database.readConcern === "majority",
    "Primary majority reads were not enforced"
  );
  assert(
    report.database.consistency === "PRIMARY_MAJORITY_PER_COMMAND" &&
      report.database.snapshotGuaranteed === false &&
      report.database.driftCheck ===
        "COUNT_AND_AUDITED_FIELD_FINGERPRINT",
    "Snapshot and drift-check limits were not recorded"
  );
  assert(
    report.safety.readRoleVerified === true,
    "Read-only role was not verified"
  );
  assert(
    report.safety.writeCapableStagesRequested === 0,
    "Write-capable stage count must be zero"
  );
  assert(
    report.safety.rawIdentifiersEmitted === false,
    "Raw identifier policy changed"
  );
  assert(
    report.policy.ownerRelations === 5 &&
      report.policy.defaultTemplateReferenceFields === 2,
    "Audit scope must remain 5 ownership relations and 2 references"
  );
  assert(
    report.policy.cleanupAuthorized === false,
    "Dry-run must not authorize cleanup"
  );

  assertRelation(report, "retrospectives", {
    scannedDocuments: 6,
    distinctMissingOwners: 1,
    counts: {
      valid: 3,
      orphan: 1,
      missingRequired: 1,
      absentAllowed: 0,
      invalidReference: 1,
      ambiguousParent: 0,
    },
  });
  assertRelation(report, "feedbacks", {
    scannedDocuments: 6,
    distinctMissingOwners: 1,
    counts: {
      valid: 3,
      orphan: 1,
      missingRequired: 1,
      absentAllowed: 0,
      invalidReference: 1,
      ambiguousParent: 0,
    },
  });
  assertRelation(report, "templates", {
    scannedDocuments: 6,
    distinctMissingOwners: 1,
    counts: {
      valid: 4,
      orphan: 1,
      missingRequired: 1,
      absentAllowed: 0,
      invalidReference: 0,
      ambiguousParent: 0,
    },
  });
  assertRelation(report, "logs", {
    scannedDocuments: 8,
    distinctMissingOwners: 1,
    counts: {
      valid: 3,
      orphan: 1,
      missingRequired: 0,
      absentAllowed: 2,
      invalidReference: 2,
      ambiguousParent: 0,
    },
  });
  assertRelation(report, "password_reset_codes", {
    scannedDocuments: 7,
    distinctMissingOwners: 2,
    counts: {
      valid: 3,
      orphan: 2,
      missingRequired: 1,
      absentAllowed: 0,
      invalidReference: 1,
      ambiguousParent: 0,
    },
  });

  const passwordRelation = relationByCollection(
    report,
    "password_reset_codes"
  );
  assert(
    passwordRelation.passwordResetExpiry.expiredCandidates === 1,
    "Expired password candidate mismatch"
  );
  assert(
    passwordRelation.passwordResetExpiry.activeCandidates === 1,
    "Active password candidate mismatch"
  );
  assert(
    passwordRelation.passwordResetExpiry.unknownExpiryCandidates === 0,
    "Unknown password expiry mismatch"
  );

  const success = referenceByField(
    report,
    "defaultSuccessTemplateId"
  );
  assert(success.scannedStudents === 5, "Success scan count mismatch");
  assert(success.counts.validSystem === 1, "Success system mismatch");
  assert(
    success.counts.validOwnedCustom === 2,
    "Success owned custom mismatch"
  );
  assert(success.counts.danglingTarget === 1, "Success dangling mismatch");
  assert(success.counts.invalidReference === 1, "Success invalid mismatch");

  const fail = referenceByField(report, "defaultFailTemplateId");
  assert(fail.scannedStudents === 5, "Fail scan count mismatch");
  assert(fail.counts.absentAllowed === 1, "Fail absent mismatch");
  assert(fail.counts.validOwnedCustom === 1, "Fail owned custom mismatch");
  assert(
    fail.counts.foreignCustomTarget === 1,
    "Fail foreign custom mismatch"
  );
  assert(
    fail.counts.invalidTargetTypeOrShape === 1,
    "Fail invalid target mismatch"
  );
  assert(fail.counts.invalidReference === 1, "Fail invalid ref mismatch");

  const findings = report.findings;
  assert(
    findings.preflight.templateShape.systemTemplates === 2,
    "System template count mismatch"
  );
  assert(
    findings.preflight.templateShape.schemaAnomalyOccurrences === 3,
    "Template shape anomaly count mismatch"
  );
  assert(
    findings.exclusions.legacyOwnerlessLogs === 2,
    "Legacy ownerless log count mismatch"
  );
  assert(
    findings.exclusions.adminAuditLogs === "NOT_SCANNED",
    "Admin audit policy mismatch"
  );
  assert(findings.countDriftDetected === false, "Count drift detected");
  assert(
    findings.auditedFieldFingerprintDriftDetected === false,
    "Audited field fingerprint drift detected"
  );
  assert(
    JSON.stringify(findings.countsBefore) ===
      JSON.stringify(findings.countsAfter),
    "Collection counts changed during dry-run"
  );
  assert(
    JSON.stringify(findings.auditedFieldFingerprintsBefore) ===
      JSON.stringify(findings.auditedFieldFingerprintsAfter),
    "Audited field fingerprints changed during dry-run"
  );
  const fingerprintCollections = [
    "students",
    "templates",
    "retrospectives",
    "feedbacks",
    "logs",
    "password_reset_codes",
  ];
  assert(
    JSON.stringify(
      Object.keys(findings.auditedFieldFingerprintsBefore)
    ) === JSON.stringify(fingerprintCollections),
    "Unexpected audited field fingerprint scope"
  );
  fingerprintCollections.forEach((collection) => {
    const fingerprint =
      findings.auditedFieldFingerprintsBefore[collection];
    assert(
      Number.isSafeInteger(fingerprint.documents) &&
        fingerprint.documents >= 0 &&
        /^[0-9a-f]{64}$/.test(fingerprint.sha256),
      "Invalid audited field fingerprint"
    );
    assert(
      fingerprint.documents === findings.countsBefore[collection],
      `${collection} fingerprint document count mismatch`
    );
  });
  assert(findings.totals.orphanDocuments === 6, "Orphan total mismatch");
  assert(
    findings.totals.danglingDefaultReferences === 1,
    "Dangling default total mismatch"
  );
  assert(
    findings.totals.foreignCustomDefaultReferences === 1,
    "Foreign default total mismatch"
  );
  assert(
    findings.totals.schemaAnomalyOccurrences === 14,
    "Schema anomaly total mismatch"
  );
  assert(
    findings.totals.candidateLogicalBsonBytes > 0,
    "Candidate logical BSON bytes must be positive"
  );
  assert(
    /^[0-9a-f]{64}$/.test(report.resultSha256),
    "Result SHA-256 is invalid"
  );
  assert(
    report.source.commitSha === expectedCommitSha,
    "Commit SHA does not match the verification source"
  );
  assert(
    report.source.harnessSha256 === expectedHarnessSha,
    "Harness SHA-256 does not match the verification source"
  );
  assert(
    report.source.mongoImage === EXPECTED_MONGO_IMAGE,
    "Pinned MongoDB image was not recorded"
  );
  if (process.env.ORPHAN_DRY_RUN_EXPECT_CLEAN_SOURCE === "true") {
    assert(
      report.source.gitDirty === false,
      "Publication verification requires a clean source"
    );
  }
}

const [
  firstReportPath,
  secondReportPath,
  beforeSnapshotPath,
  afterSnapshotPath,
  roleAbortedReportPath,
  studentCollisionReportPath,
  templateCollisionReportPath,
  expectedCommitSha,
  expectedHarnessSha,
] = process.argv.slice(2);

const firstRaw = fs.readFileSync(firstReportPath, "utf8").trim();
const secondRaw = fs.readFileSync(secondReportPath, "utf8").trim();
const first = JSON.parse(firstRaw);
const second = JSON.parse(secondRaw);
assert(
  /^[0-9a-f]{40}$/.test(expectedCommitSha),
  "Expected commit SHA is invalid"
);
assert(
  /^[0-9a-f]{64}$/.test(expectedHarnessSha),
  "Expected harness SHA-256 is invalid"
);
verifyCompleteReport(first, expectedCommitSha, expectedHarnessSha);
verifyCompleteReport(second, expectedCommitSha, expectedHarnessSha);
assert(
  first.resultSha256 === second.resultSha256,
  "Repeated dry-run result hashes differ"
);
assert(
  fs.readFileSync(beforeSnapshotPath, "utf8").trim() ===
    fs.readFileSync(afterSnapshotPath, "utf8").trim(),
  "MongoDB documents changed during dry-run"
);

const forbiddenIdentifiers = [
  "student-string",
  "missing-owner",
  "64b64b64b64b64b64b64b001",
  "64b64b64b64b64b64b64b101",
  "ABCDEFABCDEFABCDEFABCDEF",
  "FEDCBAFEDCBAFEDCBAFEDCBA",
  "64B64B64B64B64B64B64B999",
];
forbiddenIdentifiers.forEach((identifier) => {
  assert(
    !firstRaw.includes(identifier) && !secondRaw.includes(identifier),
    `Raw identifier leaked: ${identifier}`
  );
});

function verifyCollisionReport(path, collection) {
  const raw = fs.readFileSync(path, "utf8").trim();
  const report = JSON.parse(raw);
  assert(
    report.completed === false,
    `${collection} collision report must be incomplete`
  );
  assert(
    report.status === "ABORTED",
    `${collection} collision report must abort`
  );
  assert(
    report.decision === "BLOCKED",
    `${collection} collision report must block`
  );
  assert(
    report.abortReasons[0].code ===
      "CANONICAL_PARENT_ID_COLLISION",
    `Unexpected ${collection} collision abort reason`
  );
  assert(
    report.abortReasons[0].details.collection === collection,
    `Unexpected ${collection} collision details`
  );
  assert(
    report.abortReasons[0].details.documents === 1,
    `Unexpected ${collection} collision count`
  );
  assert(
    !raw.toLowerCase().includes("64b64b64b64b64b64b64b999"),
    `${collection} collision identifier leaked`
  );
}

verifyCollisionReport(studentCollisionReportPath, "students");
verifyCollisionReport(templateCollisionReportPath, "templates");

const roleAborted = readJson(roleAbortedReportPath);
assert(
  roleAborted.completed === false &&
    roleAborted.status === "ABORTED" &&
    roleAborted.decision === "BLOCKED",
  "Write-capable role report must abort"
);
assert(
  roleAborted.abortReasons[0].code === "REMOTE_READ_ROLE_REQUIRED",
  "Unexpected write-capable role abort reason"
);
