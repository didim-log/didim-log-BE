const crypto = require("crypto");

const SCHEMA_VERSION = "didimlog-orphan-dry-run/v1";
const MODE = "READ_ONLY_DRY_RUN";
const RESERVED_DATABASES = new Set(["admin", "config", "local"]);
const MAX_TIME_MS = Number(process.env.ORPHAN_DRY_RUN_MAX_TIME_MS || "30000");
const EXPECTED_DATABASE = process.env.ORPHAN_DRY_RUN_EXPECTED_DATABASE;
const TARGET_SCOPE = process.env.ORPHAN_DRY_RUN_TARGET_SCOPE;
const RUN_ID = process.env.ORPHAN_DRY_RUN_RUN_ID;
const COMMIT_SHA = process.env.ORPHAN_DRY_RUN_COMMIT_SHA;
const GIT_DIRTY = process.env.ORPHAN_DRY_RUN_GIT_DIRTY;
const HARNESS_SHA256 = process.env.ORPHAN_DRY_RUN_HARNESS_SHA256;
const MONGO_IMAGE = process.env.ORPHAN_DRY_RUN_MONGO_IMAGE || null;
const QUERY_COMMENT = "didimlog-orphan-dry-run-v1";
const AGGREGATE_OPTIONS = {
  allowDiskUse: false,
  maxTimeMS: MAX_TIME_MS,
  comment: QUERY_COMMENT,
  readConcern: { level: "majority" },
};
const COUNT_OPTIONS = {
  maxTimeMS: MAX_TIME_MS,
  comment: QUERY_COMMENT,
  readConcern: { level: "majority" },
};

const OWNERSHIP_RELATIONS = [
  {
    name: "retrospectives.studentId->students._id",
    sourceCollection: "retrospectives",
    referenceField: "studentId",
    scope: "ALL",
    absentAllowed: false,
  },
  {
    name: "feedbacks.writerId->students._id",
    sourceCollection: "feedbacks",
    referenceField: "writerId",
    scope: "ALL",
    absentAllowed: false,
  },
  {
    name: "templates.studentId->students._id",
    sourceCollection: "templates",
    referenceField: "studentId",
    scope: "CUSTOM_ONLY",
    absentAllowed: false,
    sourceMatch: { type: "CUSTOM" },
  },
  {
    name: "logs.studentId->students._id",
    sourceCollection: "logs",
    referenceField: "studentId",
    scope: "NON_NULL_OWNER",
    absentAllowed: true,
  },
  {
    name: "password_reset_codes.studentId->students._id",
    sourceCollection: "password_reset_codes",
    referenceField: "studentId",
    scope: "ALL",
    absentAllowed: false,
    expiryField: "expiresAt",
  },
];

const DEFAULT_REFERENCE_FIELDS = [
  "defaultSuccessTemplateId",
  "defaultFailTemplateId",
];

const INSPECTED_COLLECTIONS = [
  "students",
  "templates",
  "retrospectives",
  "feedbacks",
  "logs",
  "password_reset_codes",
];

const AUDITED_FIELD_PROJECTIONS = {
  students: {
    _id: 1,
    defaultSuccessTemplateId: 1,
    defaultFailTemplateId: 1,
  },
  templates: {
    _id: 1,
    type: 1,
    studentId: 1,
  },
  retrospectives: {
    _id: 1,
    studentId: 1,
  },
  feedbacks: {
    _id: 1,
    writerId: 1,
  },
  logs: {
    _id: 1,
    studentId: 1,
  },
  password_reset_codes: {
    _id: 1,
    studentId: 1,
    expiresAt: 1,
  },
};

const OWNERSHIP_STATUSES = [
  "VALID",
  "ORPHAN",
  "MISSING_REQUIRED",
  "ABSENT_ALLOWED",
  "INVALID_REFERENCE",
  "AMBIGUOUS_PARENT",
];

const DEFAULT_REFERENCE_STATUSES = [
  "ABSENT_ALLOWED",
  "VALID_SYSTEM",
  "VALID_OWN_CUSTOM",
  "DANGLING_TARGET",
  "FOREIGN_CUSTOM_TARGET",
  "INVALID_REFERENCE",
  "INVALID_TARGET_TYPE_OR_SHAPE",
  "AMBIGUOUS_TARGET",
];

class AuditAbort extends Error {
  constructor(code, details = {}) {
    super(code);
    this.name = "AuditAbort";
    this.code = code;
    this.details = details;
  }
}

function requireCondition(condition, code, details = {}) {
  if (!condition) {
    throw new AuditAbort(code, details);
  }
}

function toSafeCount(value, label) {
  const number = Number(value || 0);
  requireCondition(
    Number.isSafeInteger(number) && number >= 0,
    "COUNT_OUT_OF_SAFE_RANGE",
    { label }
  );
  return number;
}

function stableValue(value) {
  if (Array.isArray(value)) {
    return value.map(stableValue);
  }
  if (value && typeof value === "object") {
    return Object.keys(value)
      .sort()
      .reduce((result, key) => {
        result[key] = stableValue(value[key]);
        return result;
      }, {});
  }
  return value;
}

function sha256(value) {
  return crypto
    .createHash("sha256")
    .update(JSON.stringify(stableValue(value)))
    .digest("hex");
}

function supportedReference(path) {
  return {
    $or: [
      { $eq: [{ $type: path }, "objectId"] },
      {
        $cond: [
          { $eq: [{ $type: path }, "string"] },
          {
            $gt: [
              {
                $strLenCP: {
                  $trim: {
                    input: path,
                  },
                },
              },
              0,
            ],
          },
          false,
        ],
      },
    ],
  };
}

function idCandidates(path) {
  return {
    $cond: [
      supportedReference(path),
      {
        $setDifference: [
          [
            {
              $convert: {
                input: path,
                to: "string",
                onError: null,
                onNull: null,
              },
            },
            {
              $convert: {
                input: path,
                to: "objectId",
                onError: null,
                onNull: null,
              },
            },
          ],
          [null],
        ],
      },
      [],
    ],
  };
}

function collectionPreflight(auditDb) {
  const infoByName = new Map(
    auditDb
      .getCollectionInfos()
      .filter((info) => INSPECTED_COLLECTIONS.includes(info.name))
      .map((info) => [info.name, info.type])
  );

  const collections = INSPECTED_COLLECTIONS.map((name) => ({
    name,
    state: infoByName.has(name)
      ? infoByName.get(name) === "collection"
        ? "COLLECTION"
        : "UNSUPPORTED_VIEW"
      : "MISSING_AS_EMPTY",
  }));

  const students = collections.find((entry) => entry.name === "students");
  const templates = collections.find((entry) => entry.name === "templates");
  requireCondition(
    students.state === "COLLECTION",
    "STUDENTS_COLLECTION_REQUIRED",
    { state: students.state }
  );
  requireCondition(
    templates.state === "COLLECTION",
    "TEMPLATES_COLLECTION_REQUIRED",
    { state: templates.state }
  );
  requireCondition(
    collections.every((entry) => entry.state !== "UNSUPPORTED_VIEW"),
    "VIEW_SOURCE_NOT_SUPPORTED",
    {
      collections: collections
        .filter((entry) => entry.state === "UNSUPPORTED_VIEW")
        .map((entry) => entry.name),
    }
  );

  return collections;
}

function parentIdProfile(auditDb, collectionName) {
  const typeRows = auditDb
    .getCollection(collectionName)
    .aggregate(
      [
        {
          $group: {
            _id: { $type: "$_id" },
            documents: { $sum: 1 },
          },
        },
        {
          $project: {
            _id: 0,
            type: "$_id",
            documents: 1,
          },
        },
        { $sort: { type: 1 } },
      ],
      AGGREGATE_OPTIONS
    )
    .toArray()
    .map((row) => ({
      type: row.type,
      documents: toSafeCount(
        row.documents,
        `${collectionName}.idTypes.${row.type}`
      ),
    }));

  const collisionRows = auditDb
    .getCollection(collectionName)
    .aggregate(
      [
        {
          $match: {
            $expr: {
              $in: [{ $type: "$_id" }, ["string", "objectId"]],
            },
          },
        },
        {
          $project: {
            canonicalId: {
              $let: {
                vars: {
                  objectId: {
                    $convert: {
                      input: "$_id",
                      to: "objectId",
                      onError: null,
                      onNull: null,
                    },
                  },
                },
                in: {
                  $cond: [
                    { $ne: ["$$objectId", null] },
                    {
                      $convert: {
                        input: "$$objectId",
                        to: "string",
                        onError: null,
                        onNull: null,
                      },
                    },
                    {
                      $convert: {
                        input: "$_id",
                        to: "string",
                        onError: null,
                        onNull: null,
                      },
                    },
                  ],
                },
              },
            },
            idType: { $type: "$_id" },
          },
        },
        {
          $group: {
            _id: "$canonicalId",
            idTypes: { $addToSet: "$idType" },
          },
        },
        {
          $match: {
            $expr: {
              $gt: [{ $size: "$idTypes" }, 1],
            },
          },
        },
        { $count: "documents" },
      ],
      AGGREGATE_OPTIONS
    )
    .toArray();

  const supportedTypes = new Set(["string", "objectId"]);
  const unsupportedIdDocuments = typeRows
    .filter((row) => !supportedTypes.has(row.type))
    .reduce((sum, row) => sum + row.documents, 0);
  const canonicalCollisionCount = toSafeCount(
    collisionRows[0]?.documents,
    `${collectionName}.canonicalCollisionCount`
  );

  return {
    collection: collectionName,
    documents: typeRows.reduce((sum, row) => sum + row.documents, 0),
    idTypes: typeRows,
    unsupportedIdDocuments,
    canonicalCollisionCount,
  };
}

function templateShape(auditDb) {
  const templates = auditDb.getCollection("templates");
  const systemTemplates = toSafeCount(
    templates.countDocuments({ type: "SYSTEM" }, COUNT_OPTIONS),
    "templates.systemTemplates"
  );
  const systemWithOwner = toSafeCount(
    templates.countDocuments(
      {
        type: "SYSTEM",
        studentId: { $exists: true, $ne: null },
      },
      COUNT_OPTIONS
    ),
    "templates.systemWithOwner"
  );
  const customWithoutValidOwner = toSafeCount(
    templates.countDocuments(
      {
        type: "CUSTOM",
        $expr: {
          $not: [supportedReference("$studentId")],
        },
      },
      COUNT_OPTIONS
    ),
    "templates.customWithoutValidOwner"
  );
  const unknownType = toSafeCount(
    templates.countDocuments(
      {
        type: { $nin: ["SYSTEM", "CUSTOM"] },
      },
      COUNT_OPTIONS
    ),
    "templates.unknownType"
  );

  return {
    systemTemplates,
    systemWithOwner,
    customWithoutValidOwner,
    unknownType,
    schemaAnomalyOccurrences:
      systemWithOwner + customWithoutValidOwner + unknownType,
  };
}

function analyzeOwnership(auditDb, specification) {
  const referencePath = `$${specification.referenceField}`;
  const pipeline = [];
  if (specification.sourceMatch) {
    pipeline.push({ $match: specification.sourceMatch });
  }
  pipeline.push(
    {
      $set: {
        __logicalBsonBytes: { $bsonSize: "$$ROOT" },
        __referenceType: { $type: referencePath },
        __referenceCandidates: idCandidates(referencePath),
      },
    },
    {
      $lookup: {
        from: "students",
        localField: "__referenceCandidates",
        foreignField: "_id",
        pipeline: [{ $project: { _id: 1 } }, { $limit: 2 }],
        as: "__parents",
      },
    },
    {
      $set: {
        __parentCount: { $size: "$__parents" },
        __status: {
          $switch: {
            branches: [
              {
                case: {
                  $in: ["$__referenceType", ["missing", "null"]],
                },
                then: specification.absentAllowed
                  ? "ABSENT_ALLOWED"
                  : "MISSING_REQUIRED",
              },
              {
                case: { $not: [supportedReference(referencePath)] },
                then: "INVALID_REFERENCE",
              },
              {
                case: { $eq: [{ $size: "$__parents" }, 0] },
                then: "ORPHAN",
              },
              {
                case: { $eq: [{ $size: "$__parents" }, 1] },
                then: "VALID",
              },
            ],
            default: "AMBIGUOUS_PARENT",
          },
        },
      },
    },
    {
      $facet: {
        statusCounts: [
          {
            $group: {
              _id: "$__status",
              documents: { $sum: 1 },
              logicalBsonBytes: { $sum: "$__logicalBsonBytes" },
              expiredOrphanDocuments: {
                $sum: {
                  $cond: [
                    {
                      $and: [
                        { $eq: ["$__status", "ORPHAN"] },
                        {
                          $eq: [
                            {
                              $type: specification.expiryField
                                ? `$${specification.expiryField}`
                                : null,
                            },
                            "date",
                          ],
                        },
                        {
                          $lt: [
                            specification.expiryField
                              ? `$${specification.expiryField}`
                              : null,
                            "$$NOW",
                          ],
                        },
                      ],
                    },
                    1,
                    0,
                  ],
                },
              },
              activeOrphanDocuments: {
                $sum: {
                  $cond: [
                    {
                      $and: [
                        { $eq: ["$__status", "ORPHAN"] },
                        {
                          $eq: [
                            {
                              $type: specification.expiryField
                                ? `$${specification.expiryField}`
                                : null,
                            },
                            "date",
                          ],
                        },
                        {
                          $gte: [
                            specification.expiryField
                              ? `$${specification.expiryField}`
                              : null,
                            "$$NOW",
                          ],
                        },
                      ],
                    },
                    1,
                    0,
                  ],
                },
              },
              unknownExpiryOrphanDocuments: {
                $sum: {
                  $cond: [
                    {
                      $and: [
                        { $eq: ["$__status", "ORPHAN"] },
                        {
                          $ne: [
                            {
                              $type: specification.expiryField
                                ? `$${specification.expiryField}`
                                : null,
                            },
                            "date",
                          ],
                        },
                      ],
                    },
                    1,
                    0,
                  ],
                },
              },
            },
          },
        ],
        orphanOwners: [
          { $match: { __status: "ORPHAN" } },
          {
            $group: {
              _id: {
                $convert: {
                  input: referencePath,
                  to: "string",
                  onError: null,
                  onNull: null,
                },
              },
            },
          },
          { $count: "documents" },
        ],
      },
    }
  );

  const result = auditDb
    .getCollection(specification.sourceCollection)
    .aggregate(pipeline, AGGREGATE_OPTIONS)
    .toArray()[0] || { statusCounts: [], orphanOwners: [] };
  const counts = Object.fromEntries(
    OWNERSHIP_STATUSES.map((status) => [status, 0])
  );
  const logicalBytesByStatus = Object.fromEntries(
    OWNERSHIP_STATUSES.map((status) => [status, 0])
  );
  let expiredOrphanDocuments = 0;
  let activeOrphanDocuments = 0;
  let unknownExpiryOrphanDocuments = 0;

  result.statusCounts.forEach((row) => {
    requireCondition(
      OWNERSHIP_STATUSES.includes(row._id),
      "UNKNOWN_OWNERSHIP_STATUS",
      { relation: specification.name }
    );
    counts[row._id] = toSafeCount(
      row.documents,
      `${specification.name}.${row._id}`
    );
    logicalBytesByStatus[row._id] = toSafeCount(
      row.logicalBsonBytes,
      `${specification.name}.${row._id}.logicalBsonBytes`
    );
    if (row._id === "ORPHAN" && specification.expiryField) {
      expiredOrphanDocuments = toSafeCount(
        row.expiredOrphanDocuments,
        `${specification.name}.expiredOrphanDocuments`
      );
      activeOrphanDocuments = toSafeCount(
        row.activeOrphanDocuments,
        `${specification.name}.activeOrphanDocuments`
      );
      unknownExpiryOrphanDocuments = toSafeCount(
        row.unknownExpiryOrphanDocuments,
        `${specification.name}.unknownExpiryOrphanDocuments`
      );
    }
  });

  const scannedDocuments = Object.values(counts).reduce(
    (sum, count) => sum + count,
    0
  );
  const distinctMissingOwners = toSafeCount(
    result.orphanOwners[0]?.documents,
    `${specification.name}.distinctMissingOwners`
  );
  const schemaAnomalyOccurrences =
    counts.MISSING_REQUIRED +
    counts.INVALID_REFERENCE +
    counts.AMBIGUOUS_PARENT;

  return {
    name: specification.name,
    sourceCollection: specification.sourceCollection,
    referenceField: specification.referenceField,
    scope: specification.scope,
    scannedDocuments,
    ownerBearingDocuments:
      counts.VALID + counts.ORPHAN + counts.AMBIGUOUS_PARENT,
    counts: {
      valid: counts.VALID,
      orphan: counts.ORPHAN,
      missingRequired: counts.MISSING_REQUIRED,
      absentAllowed: counts.ABSENT_ALLOWED,
      invalidReference: counts.INVALID_REFERENCE,
      ambiguousParent: counts.AMBIGUOUS_PARENT,
    },
    distinctMissingOwners,
    candidateLogicalBsonBytes: logicalBytesByStatus.ORPHAN,
    schemaAnomalyOccurrences,
    passwordResetExpiry: specification.expiryField
      ? {
          expiredCandidates: expiredOrphanDocuments,
          activeCandidates: activeOrphanDocuments,
          unknownExpiryCandidates: unknownExpiryOrphanDocuments,
        }
      : null,
  };
}

function analyzeDefaultReference(auditDb, referenceField) {
  const referencePath = `$${referenceField}`;
  const pipeline = [
    {
      $set: {
        __referenceType: { $type: referencePath },
        __referenceCandidates: idCandidates(referencePath),
        __studentIdCandidates: idCandidates("$_id"),
      },
    },
    {
      $lookup: {
        from: "templates",
        localField: "__referenceCandidates",
        foreignField: "_id",
        pipeline: [
          {
            $project: {
              _id: 1,
              type: 1,
              studentId: 1,
            },
          },
          { $limit: 2 },
        ],
        as: "__targets",
      },
    },
    {
      $set: {
        __targetCount: { $size: "$__targets" },
        __target: { $arrayElemAt: ["$__targets", 0] },
      },
    },
    {
      $set: {
        __targetOwnerCandidates: idCandidates("$__target.studentId"),
        __targetOwnerType: { $type: "$__target.studentId" },
      },
    },
    {
      $set: {
        __status: {
          $switch: {
            branches: [
              {
                case: {
                  $in: ["$__referenceType", ["missing", "null"]],
                },
                then: "ABSENT_ALLOWED",
              },
              {
                case: { $not: [supportedReference(referencePath)] },
                then: "INVALID_REFERENCE",
              },
              {
                case: { $eq: ["$__targetCount", 0] },
                then: "DANGLING_TARGET",
              },
              {
                case: { $gt: ["$__targetCount", 1] },
                then: "AMBIGUOUS_TARGET",
              },
              {
                case: {
                  $and: [
                    { $eq: ["$__target.type", "SYSTEM"] },
                    {
                      $in: [
                        "$__targetOwnerType",
                        ["missing", "null"],
                      ],
                    },
                  ],
                },
                then: "VALID_SYSTEM",
              },
              {
                case: {
                  $and: [
                    { $eq: ["$__target.type", "CUSTOM"] },
                    supportedReference("$__target.studentId"),
                    {
                      $gt: [
                        {
                          $size: {
                            $setIntersection: [
                              "$__targetOwnerCandidates",
                              "$__studentIdCandidates",
                            ],
                          },
                        },
                        0,
                      ],
                    },
                  ],
                },
                then: "VALID_OWN_CUSTOM",
              },
              {
                case: {
                  $and: [
                    { $eq: ["$__target.type", "CUSTOM"] },
                    supportedReference("$__target.studentId"),
                  ],
                },
                then: "FOREIGN_CUSTOM_TARGET",
              },
            ],
            default: "INVALID_TARGET_TYPE_OR_SHAPE",
          },
        },
      },
    },
    {
      $group: {
        _id: "$__status",
        documents: { $sum: 1 },
      },
    },
  ];

  const rows = auditDb
    .getCollection("students")
    .aggregate(pipeline, AGGREGATE_OPTIONS)
    .toArray();
  const counts = Object.fromEntries(
    DEFAULT_REFERENCE_STATUSES.map((status) => [status, 0])
  );
  rows.forEach((row) => {
    requireCondition(
      DEFAULT_REFERENCE_STATUSES.includes(row._id),
      "UNKNOWN_DEFAULT_REFERENCE_STATUS",
      { referenceField }
    );
    counts[row._id] = toSafeCount(
      row.documents,
      `${referenceField}.${row._id}`
    );
  });

  const scannedStudents = Object.values(counts).reduce(
    (sum, count) => sum + count,
    0
  );

  return {
    referenceField,
    scannedStudents,
    referenceOccurrences:
      scannedStudents - counts.ABSENT_ALLOWED,
    counts: {
      absentAllowed: counts.ABSENT_ALLOWED,
      validSystem: counts.VALID_SYSTEM,
      validOwnedCustom: counts.VALID_OWN_CUSTOM,
      danglingTarget: counts.DANGLING_TARGET,
      foreignCustomTarget: counts.FOREIGN_CUSTOM_TARGET,
      invalidReference: counts.INVALID_REFERENCE,
      invalidTargetTypeOrShape:
        counts.INVALID_TARGET_TYPE_OR_SHAPE,
      ambiguousTarget: counts.AMBIGUOUS_TARGET,
    },
    schemaAnomalyOccurrences:
      counts.INVALID_REFERENCE +
      counts.INVALID_TARGET_TYPE_OR_SHAPE +
      counts.AMBIGUOUS_TARGET,
  };
}

function captureCollectionCounts(auditDb) {
  return Object.fromEntries(
    INSPECTED_COLLECTIONS.map((collectionName) => [
      collectionName,
      toSafeCount(
        auditDb
          .getCollection(collectionName)
          .countDocuments({}, COUNT_OPTIONS),
        `${collectionName}.count`
      ),
    ])
  );
}

function captureAuditedFieldFingerprints(auditDb) {
  return Object.fromEntries(
    INSPECTED_COLLECTIONS.map((collectionName) => {
      const hash = crypto.createHash("sha256");
      let documents = 0;
      auditDb
        .getCollection(collectionName)
        .aggregate(
          [
            {
              $project: AUDITED_FIELD_PROJECTIONS[collectionName],
            },
            { $sort: { _id: 1 } },
          ],
          AGGREGATE_OPTIONS
        )
        .forEach((document) => {
          hash.update(EJSON.stringify(document, { relaxed: false }));
          hash.update("\n");
          documents += 1;
        });

      return [
        collectionName,
        {
          documents: toSafeCount(
            documents,
            `${collectionName}.fingerprintDocuments`
          ),
          sha256: hash.digest("hex"),
        },
      ];
    })
  );
}

function verifyConfiguration() {
  requireCondition(
    OWNERSHIP_RELATIONS.length === 5,
    "OWNERSHIP_RELATION_CONFIGURATION_INVALID"
  );
  requireCondition(
    DEFAULT_REFERENCE_FIELDS.length === 2,
    "DEFAULT_REFERENCE_CONFIGURATION_INVALID"
  );
  requireCondition(
    new Set(OWNERSHIP_RELATIONS.map((entry) => entry.name)).size === 5,
    "DUPLICATE_OWNERSHIP_RELATION"
  );
  requireCondition(
    new Set(DEFAULT_REFERENCE_FIELDS).size === 2,
    "DUPLICATE_DEFAULT_REFERENCE"
  );
}

function authenticatedReadRole(auditDb) {
  const connectionStatus = auditDb.runCommand({
    connectionStatus: 1,
    showPrivileges: false,
  });
  requireCondition(
    connectionStatus.ok === 1,
    "CONNECTION_STATUS_UNAVAILABLE"
  );
  const roles =
    connectionStatus.authInfo?.authenticatedUserRoles || [];
  return (
    roles.length === 1 &&
    roles[0].role === "read" &&
    roles[0].db === auditDb.getName()
  );
}

function executeAudit() {
  requireCondition(
    typeof EXPECTED_DATABASE === "string" &&
      EXPECTED_DATABASE.length > 0,
    "EXPECTED_DATABASE_REQUIRED"
  );
  requireCondition(
    db.getName() === EXPECTED_DATABASE,
    "DATABASE_NAME_MISMATCH",
    {
      expected: EXPECTED_DATABASE,
      actual: db.getName(),
    }
  );
  requireCondition(
    !RESERVED_DATABASES.has(EXPECTED_DATABASE),
    "RESERVED_DATABASE_REJECTED"
  );
  requireCondition(
    TARGET_SCOPE === "local-fixture" ||
      TARGET_SCOPE === "remote-read-only",
    "TARGET_SCOPE_INVALID"
  );
  requireCondition(
    Number.isSafeInteger(MAX_TIME_MS) &&
      MAX_TIME_MS >= 1000 &&
      MAX_TIME_MS <= 300000,
    "MAX_TIME_MS_INVALID"
  );
  requireCondition(
    typeof RUN_ID === "string" && RUN_ID.length > 0,
    "RUN_ID_REQUIRED"
  );
  requireCondition(
    typeof COMMIT_SHA === "string" &&
      /^[0-9a-f]{40}$/.test(COMMIT_SHA),
    "COMMIT_SHA_INVALID"
  );
  requireCondition(
    GIT_DIRTY === "true" || GIT_DIRTY === "false",
    "GIT_DIRTY_INVALID"
  );
  requireCondition(
    typeof HARNESS_SHA256 === "string" &&
      /^[0-9a-f]{64}$/.test(HARNESS_SHA256),
    "HARNESS_SHA256_INVALID"
  );

  verifyConfiguration();
  const auditDb = db;
  const configuredReadPreference =
    auditDb.getMongo().getReadPrefMode();
  auditDb.getMongo().setReadPref("primary");
  const effectiveReadPreference =
    auditDb.getMongo().getReadPrefMode();
  requireCondition(
    effectiveReadPreference === "primary",
    "PRIMARY_READ_PREFERENCE_REQUIRED"
  );
  const readRoleVerified = authenticatedReadRole(auditDb);
  if (TARGET_SCOPE === "remote-read-only") {
    requireCondition(
      readRoleVerified,
      "REMOTE_READ_ROLE_REQUIRED"
    );
  }

  const collections = collectionPreflight(auditDb);
  const countsBefore = captureCollectionCounts(auditDb);
  const auditedFieldFingerprintsBefore =
    captureAuditedFieldFingerprints(auditDb);
  const studentsIdProfile = parentIdProfile(auditDb, "students");
  const templatesIdProfile = parentIdProfile(auditDb, "templates");
  [studentsIdProfile, templatesIdProfile].forEach((profile) => {
    requireCondition(
      profile.unsupportedIdDocuments === 0,
      "UNSUPPORTED_PARENT_ID_TYPE",
      {
        collection: profile.collection,
        documents: profile.unsupportedIdDocuments,
      }
    );
    requireCondition(
      profile.canonicalCollisionCount === 0,
      "CANONICAL_PARENT_ID_COLLISION",
      {
        collection: profile.collection,
        documents: profile.canonicalCollisionCount,
      }
    );
  });

  const ownershipRelations = OWNERSHIP_RELATIONS.map(
    (specification) => analyzeOwnership(auditDb, specification)
  );
  const defaultTemplateReferences = DEFAULT_REFERENCE_FIELDS.map(
    (referenceField) =>
      analyzeDefaultReference(auditDb, referenceField)
  );
  const templateShapeResult = templateShape(auditDb);
  const exclusions = {
    systemTemplates: templateShapeResult.systemTemplates,
    legacyOwnerlessLogs: toSafeCount(
      auditDb.getCollection("logs").countDocuments(
        {
          $or: [
            { studentId: { $exists: false } },
            { studentId: null },
          ],
        },
        COUNT_OPTIONS
      ),
      "logs.legacyOwnerless"
    ),
    adminAuditLogs: "NOT_SCANNED",
    logBojIdSnapshots: "NOT_SCANNED",
    problemReferences: "NOT_SCANNED",
  };
  const countsAfter = captureCollectionCounts(auditDb);
  const auditedFieldFingerprintsAfter =
    captureAuditedFieldFingerprints(auditDb);
  const countDriftDetected = INSPECTED_COLLECTIONS.some(
    (collectionName) =>
      countsBefore[collectionName] !== countsAfter[collectionName]
  );
  const auditedFieldFingerprintDriftDetected =
    INSPECTED_COLLECTIONS.some(
      (collectionName) =>
        auditedFieldFingerprintsBefore[collectionName].documents !==
          auditedFieldFingerprintsAfter[collectionName].documents ||
        auditedFieldFingerprintsBefore[collectionName].sha256 !==
          auditedFieldFingerprintsAfter[collectionName].sha256
    );

  const totals = {
    relationChecksCompleted: ownershipRelations.length,
    defaultReferenceChecksCompleted:
      defaultTemplateReferences.length,
    orphanDocuments: ownershipRelations.reduce(
      (sum, relation) => sum + relation.counts.orphan,
      0
    ),
    distinctMissingOwnersByRelation: ownershipRelations.reduce(
      (sum, relation) => sum + relation.distinctMissingOwners,
      0
    ),
    candidateLogicalBsonBytes: ownershipRelations.reduce(
      (sum, relation) =>
        sum + relation.candidateLogicalBsonBytes,
      0
    ),
    danglingDefaultReferences:
      defaultTemplateReferences.reduce(
        (sum, reference) =>
          sum + reference.counts.danglingTarget,
        0
      ),
    foreignCustomDefaultReferences:
      defaultTemplateReferences.reduce(
        (sum, reference) =>
          sum + reference.counts.foreignCustomTarget,
        0
      ),
    schemaAnomalyOccurrences:
      ownershipRelations.reduce(
        (sum, relation) =>
          sum + relation.schemaAnomalyOccurrences,
        0
      ) +
      defaultTemplateReferences.reduce(
        (sum, reference) =>
          sum + reference.schemaAnomalyOccurrences,
        0
      ) +
      templateShapeResult.systemWithOwner +
      templateShapeResult.unknownType,
  };

  const reviewRequired =
    totals.orphanDocuments > 0 ||
    totals.danglingDefaultReferences > 0 ||
    totals.foreignCustomDefaultReferences > 0 ||
    totals.schemaAnomalyOccurrences > 0;
  const decision =
    countDriftDetected || auditedFieldFingerprintDriftDetected
    ? "BLOCKED"
    : reviewRequired
      ? "REVIEW_REQUIRED"
      : "NO_FINDINGS_OBSERVED";
  const findings = {
    preflight: {
      collections,
      parentIdProfiles: [
        studentsIdProfile,
        templatesIdProfile,
      ],
      templateShape: templateShapeResult,
    },
    ownershipRelations,
    defaultTemplateReferences,
    exclusions,
    countsBefore,
    countsAfter,
    countDriftDetected,
    auditedFieldFingerprintsBefore,
    auditedFieldFingerprintsAfter,
    auditedFieldFingerprintDriftDetected,
    totals,
  };

  return {
    schemaVersion: SCHEMA_VERSION,
    mode: MODE,
    runId: RUN_ID,
    generatedAtUtc: new Date().toISOString(),
    completed: true,
    status: "COMPLETE",
    decision,
    source: {
      commitSha: COMMIT_SHA,
      gitDirty: GIT_DIRTY === "true",
      harnessSha256: HARNESS_SHA256,
      mongoImage: MONGO_IMAGE,
    },
    database: {
      name: auditDb.getName(),
      mongoVersion: auditDb.version(),
      targetScope: TARGET_SCOPE,
      consistency: "PRIMARY_MAJORITY_PER_COMMAND",
      configuredReadPreference,
      effectiveReadPreference,
      readConcern: "majority",
      snapshotGuaranteed: false,
      driftCheck: "COUNT_AND_AUDITED_FIELD_FINGERPRINT",
    },
    safety: {
      databaseNameMatched: true,
      readRoleVerified,
      allowDiskUse: false,
      maxTimeMs: MAX_TIME_MS,
      writeCapableStagesRequested: 0,
      rawIdentifiersEmitted: false,
    },
    policy: {
      ownerRelations: OWNERSHIP_RELATIONS.length,
      defaultTemplateReferenceFields:
        DEFAULT_REFERENCE_FIELDS.length,
      cleanupAuthorized: false,
    },
    findings,
    resultSha256: sha256(findings),
  };
}

function abortedReport(error) {
  const code =
    error instanceof AuditAbort
      ? error.code
      : "AUDIT_EXECUTION_FAILED";
  const details =
    error instanceof AuditAbort ? error.details : {};
  return {
    schemaVersion: SCHEMA_VERSION,
    mode: MODE,
    runId: RUN_ID || null,
    generatedAtUtc: new Date().toISOString(),
    completed: false,
    status: "ABORTED",
    decision: "BLOCKED",
    database: {
      name: typeof db !== "undefined" ? db.getName() : null,
      targetScope: TARGET_SCOPE || null,
    },
    safety: {
      writeCapableStagesRequested: 0,
      rawIdentifiersEmitted: false,
    },
    policy: {
      cleanupAuthorized: false,
    },
    abortReasons: [
      {
        code,
        details,
      },
    ],
  };
}

try {
  print(JSON.stringify(executeAudit()));
} catch (error) {
  print(JSON.stringify(abortedReport(error)));
  quit(2);
}
