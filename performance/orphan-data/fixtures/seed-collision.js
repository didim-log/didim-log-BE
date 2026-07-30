if (db.getName() !== "didimlog-orphan-fixture") {
  throw new Error("Collision fixture requires didimlog-orphan-fixture");
}

const collisionId = "64b64b64b64b64b64b64b999";
const collectionName =
  process.env.ORPHAN_COLLISION_COLLECTION || "students";
const action = process.env.ORPHAN_COLLISION_ACTION || "insert";

if (!["students", "templates"].includes(collectionName)) {
  throw new Error("Unsupported collision fixture collection");
}

const collection = db.getCollection(collectionName);
const ids = [collisionId.toUpperCase(), ObjectId(collisionId)];

if (action === "insert") {
  const documents = ids.map((_id) =>
    collectionName === "templates"
      ? { _id, type: "SYSTEM" }
      : { _id }
  );
  collection.insertMany(documents);
} else if (action === "remove") {
  collection.deleteMany({ _id: { $in: ids } });
} else {
  throw new Error("Unsupported collision fixture action");
}
