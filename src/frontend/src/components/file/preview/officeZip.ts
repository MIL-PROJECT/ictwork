import JSZip from "jszip";

/**
 * 오피스(OOXML) 파일 읽기 공통: zip을 열고 부분(xml·그림)을 읽는다. 글자만 꺼내고 스크립트·외부 연결은 따르지 않는다.
 * 압축 폭탄 막이: zip 머리글의 크기는 믿지 않고, 실제로 풀어 나오는 바이트를 세어 부분 하나·파일 전체 상한을 넘으면 멈춘다
 */
const PART_LIMIT = 50 * 1024 * 1024;
const TOTAL_LIMIT = 200 * 1024 * 1024;

export type OfficeZip = {
  /** zip 안 경로의 xml. 없으면 null */
  xml: (path: string) => Promise<Document | null>;
  /** zip 안 경로의 바이트. 없으면 null */
  bytes: (path: string) => Promise<Uint8Array<ArrayBuffer> | null>;
};

export async function openZip(data: Uint8Array): Promise<OfficeZip> {
  const zip = await JSZip.loadAsync(data);
  let total = 0;
  const read = (path: string) => {
    const entry = zip.file(path);
    if (!entry) return Promise.resolve(null);
    return new Promise<Uint8Array<ArrayBuffer>>((resolve, reject) => {
      const chunks: Uint8Array[] = [];
      let size = 0;
      // 상한을 넘겨 멈춘 뒤에도 마지막 end가 올 수 있어 다시 모으지 않는다
      let stopped = false;
      // internalStream은 jszip 3.10.1에 있으나 형 정의에 빠져 있다(lib/zipObject.js)
      const stream = (entry as unknown as { internalStream: (type: "uint8array") => JSZip.JSZipStreamHelper<Uint8Array> }).internalStream("uint8array");
      stream
        .on("data", (chunk: Uint8Array) => {
          size += chunk.length;
          total += chunk.length;
          if (stopped) return;
          if (size > PART_LIMIT || total > TOTAL_LIMIT) {
            stopped = true;
            stream.pause();
            reject(new Error("TooLarge"));
            return;
          }
          chunks.push(chunk);
        })
        .on("error", reject)
        .on("end", () => {
          if (stopped) return;
          const out = new Uint8Array(size);
          let at = 0;
          for (const chunk of chunks) {
            out.set(chunk, at);
            at += chunk.length;
          }
          resolve(out);
        })
        .resume();
    });
  };
  return {
    bytes: read,
    xml: async (path) => {
      const body = await read(path);
      if (!body) return null;
      const doc = new DOMParser().parseFromString(new TextDecoder("utf-8").decode(body), "application/xml");
      if (doc.getElementsByTagName("parsererror").length > 0) throw new Error("BadXml");
      return doc;
    },
  };
}

/** 이름공간과 관계없이 지역 이름으로 자식·자손을 찾는다 */
export const children = (node: Element | Document, name: string) =>
  Array.from(node.childNodes).filter((child): child is Element => child.nodeType === 1 && (child as Element).localName === name);
export const descendants = (node: Element | Document, name: string) =>
  Array.from(node.getElementsByTagName("*")).filter((element) => element.localName === name);
export const child = (node: Element | Document, name: string) => children(node, name)[0] ?? null;

/** 관계 파일(_rels/x.rels): Id → 대상 zip 경로. 대상은 기준 폴더에서 푼다(바깥 연결 TargetMode="External"은 뺀다) */
export async function relations(zip: OfficeZip, partPath: string): Promise<Map<string, string>> {
  const slash = partPath.lastIndexOf("/");
  const folder = partPath.slice(0, slash + 1);
  const doc = await zip.xml(`${folder}_rels/${partPath.slice(slash + 1)}.rels`);
  const out = new Map<string, string>();
  if (!doc) return out;
  for (const rel of descendants(doc, "Relationship")) {
    const id = rel.getAttribute("Id");
    const target = rel.getAttribute("Target");
    if (!id || !target || rel.getAttribute("TargetMode") === "External") continue;
    out.set(id, resolvePath(folder, target));
  }
  return out;
}

function resolvePath(folder: string, target: string) {
  const parts = (target.startsWith("/") ? target.slice(1) : folder + target).split("/");
  const out: string[] = [];
  for (const part of parts) {
    if (part === "..") out.pop();
    else if (part !== "." && part !== "") out.push(part);
  }
  return out.join("/");
}
