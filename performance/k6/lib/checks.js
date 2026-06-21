import { check } from "k6";

export function parseJson(res) {
  try {
    return res.json();
  } catch (error) {
    return null;
  }
}

export function checkStatus(res, expectedStatuses, tagSet) {
  const expected = Array.isArray(expectedStatuses) ? expectedStatuses : [expectedStatuses];
  return check(
    res,
    {
      [`status is ${expected.join(" or ")}`]: (response) => expected.includes(response.status),
    },
    tagSet
  );
}

export function checkJsonFields(res, fields, tagSet) {
  const body = parseJson(res);
  return check(
    body,
    Object.fromEntries(
      fields.map((field) => [
        `json has ${field}`,
        (json) => {
          if (json === null || json === undefined) {
            return false;
          }
          return field.split(".").every((part) => {
            if (json === null || json === undefined) {
              return false;
            }
            json = json[part];
            return json !== undefined && json !== null;
          });
        },
      ])
    ),
    tagSet
  );
}

export function isUnexpected5xx(res) {
  return res.status >= 500;
}

export function responseClass(res) {
  if (res.status === 429) {
    return "rate_limited";
  }
  if (res.status >= 500) {
    return "unexpected_5xx";
  }
  if (res.status >= 400) {
    return "expected_4xx";
  }
  return "success";
}
