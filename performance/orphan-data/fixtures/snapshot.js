const collectionNames = [
  "students",
  "templates",
  "retrospectives",
  "feedbacks",
  "logs",
  "password_reset_codes",
  "admin_audit_logs",
];

const snapshot = Object.fromEntries(
  collectionNames.map((collectionName) => [
    collectionName,
    db
      .getCollection(collectionName)
      .find({})
      .sort({ _id: 1 })
      .toArray(),
  ])
);

print(EJSON.stringify(snapshot, { relaxed: false }));
