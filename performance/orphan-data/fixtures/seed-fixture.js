const objectStudentId = ObjectId("64b64b64b64b64b64b64b001");
const objectTemplateId = ObjectId("64b64b64b64b64b64b64b101");
const objectStudentIdString = objectStudentId.toString();

db.students.insertMany([
  {
    _id: "student-string",
    defaultSuccessTemplateId: "system-template",
    defaultFailTemplateId: "owned-template",
  },
  {
    _id: objectStudentId,
    defaultSuccessTemplateId: objectTemplateId.toString(),
    defaultFailTemplateId: "foreign-template",
  },
  {
    _id: "student-missing-ref",
    defaultSuccessTemplateId: "missing-template",
    defaultFailTemplateId: "invalid-system-template",
  },
  {
    _id: "student-invalid-ref",
    defaultSuccessTemplateId: " ",
    defaultFailTemplateId: 42,
  },
]);

db.templates.insertMany([
  {
    _id: "system-template",
    type: "SYSTEM",
    title: "System",
  },
  {
    _id: "owned-template",
    type: "CUSTOM",
    studentId: "student-string",
    title: "Owned",
  },
  {
    _id: objectTemplateId,
    type: "CUSTOM",
    studentId: objectStudentIdString,
    title: "ObjectId owner",
  },
  {
    _id: "foreign-template",
    type: "CUSTOM",
    studentId: "student-string",
    title: "Foreign for object student",
  },
  {
    _id: "orphan-template",
    type: "CUSTOM",
    studentId: "missing-owner-template",
    title: "Orphan",
  },
  {
    _id: "invalid-system-template",
    type: "SYSTEM",
    studentId: "student-string",
    title: "Invalid system owner",
  },
  {
    _id: "custom-missing-owner",
    type: "CUSTOM",
    title: "Missing custom owner",
  },
  {
    _id: "unknown-template",
    type: "PARTNER",
    title: "Unknown type",
  },
]);

db.retrospectives.insertMany([
  { _id: "retrospective-string", studentId: "student-string" },
  { _id: "retrospective-object", studentId: objectStudentId },
  { _id: "retrospective-orphan", studentId: "missing-owner-retrospective" },
  { _id: "retrospective-invalid", studentId: 42 },
  { _id: "retrospective-missing" },
]);

db.feedbacks.insertMany([
  { _id: "feedback-string", writerId: "student-string" },
  { _id: "feedback-object", writerId: objectStudentIdString },
  { _id: "feedback-orphan", writerId: "missing-owner-feedback" },
  { _id: "feedback-invalid", writerId: "" },
  { _id: "feedback-missing" },
]);

db.logs.insertMany([
  { _id: "log-string", studentId: "student-string" },
  { _id: "log-object", studentId: objectStudentId },
  { _id: "log-orphan", studentId: "missing-owner-log" },
  { _id: "log-null", studentId: null },
  { _id: "log-missing" },
  { _id: "log-blank", studentId: " " },
  { _id: "log-invalid", studentId: 42 },
]);

db.password_reset_codes.insertMany([
  {
    _id: "password-string",
    studentId: "student-string",
    expiresAt: ISODate("2099-01-01T00:00:00Z"),
  },
  {
    _id: "password-object",
    studentId: objectStudentIdString,
    expiresAt: ISODate("2099-01-01T00:00:00Z"),
  },
  {
    _id: "password-orphan-expired",
    studentId: "missing-owner-password-expired",
    expiresAt: ISODate("2020-01-01T00:00:00Z"),
  },
  {
    _id: "password-orphan-active",
    studentId: "missing-owner-password-active",
    expiresAt: ISODate("2099-01-01T00:00:00Z"),
  },
  {
    _id: "password-invalid",
    studentId: "",
    expiresAt: ISODate("2099-01-01T00:00:00Z"),
  },
  {
    _id: "password-missing",
    expiresAt: ISODate("2099-01-01T00:00:00Z"),
  },
]);

db.admin_audit_logs.insertOne({
  _id: "audit-without-student",
  adminId: "deleted-admin",
  action: "HISTORICAL_RECORD",
});

db.createUser({
  user: "orphan-reader",
  pwd: "fixture-reader-password",
  roles: [{ role: "read", db: db.getName() }],
});
