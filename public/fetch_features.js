export async function fetchFeatures(bbox) {
  const [south, west, north, east] = bbox; // [s,w,n,e]
  const query = `
[out:json];
(
  way["highway"](${south},${west},${north},${east});
  way["building"](${south},${west},${north},${east});
);
out body;
>;
out skel qt;
  `;

  const response = await fetch("https://overpass-api.de/api/interpreter", {
    method: "POST",
    body: query,
  });

  const data = await response.json();
  return data;
}
