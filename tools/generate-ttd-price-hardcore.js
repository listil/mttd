// GitHub Actions 스케줄러가 주기적으로 돌리는 스크립트.
//
// TTD(ttdiablo.com)는 하드코어 시즌 시세를 다루지 않는다. 이 스크립트는 그 공백을 메우기
// 위해 ETOR 의 하드코어 시즌 스냅샷(protobuf)을 받아 TTD 스타일 flat JSON
// (`{ id: { name, type, price } }`) 으로 변환해 tools/generated/ttd_price.json 에 쓴다.
// 정규 시즌은 그대로 ttdiablo.com 을 쓰면 되므로 이 스크립트는 하드코어만 다룬다.
//
// 시즌 탐색은 app/.../PriceRepository.kt 의 sweepSeasonChain 과 동일한 규칙을 재현한다:
// 하드코어 체인은 1531 에서 시작해 100 씩 올리며 유효한(아이템 2개 이상 + 가격이 매겨진
// 아이템 2개 이상) 가장 높은 시즌을 찾는다. 시작점 자체가 이미 종료된 시즌이면 아래로
// 내려가며 찾는다 — 새 시즌이 열려도 이 파일을 고칠 필요가 없다.
//
// npm 의존성 없음 (Node 18+ 의 전역 fetch/TextDecoder/DataView 만 사용) — protobufjs 없이
// 이 API 응답 스키마 전용으로 손수 짠 디코더를 쓴다 (실제 응답 1586개 아이템 기준
// protobufjs 결과와 완전히 일치함을 확인함).
const fs = require("node:fs");
const path = require("node:path");

const HARDCORE_START_SEASON = 1531;
const SEASON_STEP = 100;
const MIN_SEASON = 1001;
const MAX_SEASON = 3001;

const NAMES_PATH = path.join(__dirname, "..", "app", "src", "main", "assets", "item_names_ko.json");
const OUT_PATH = path.join(__dirname, "generated", "ttd_price.json");

class ProtoReader {
  constructor(bytes) {
    this.buf = bytes;
    this.pos = 0;
  }
  eof() {
    return this.pos >= this.buf.length;
  }
  readVarint() {
    let result = 0, shift = 0, b;
    do {
      b = this.buf[this.pos++];
      result |= (b & 0x7f) << shift;
      shift += 7;
    } while (b & 0x80);
    return result >>> 0;
  }
  readTag() {
    const tag = this.readVarint();
    return { field: tag >>> 3, wireType: tag & 7 };
  }
  readBytes(len) {
    const slice = this.buf.subarray(this.pos, this.pos + len);
    this.pos += len;
    return slice;
  }
  readString(len) {
    return new TextDecoder("utf-8").decode(this.readBytes(len));
  }
  readFloatLE() {
    const b = this.readBytes(4);
    return new DataView(b.buffer, b.byteOffset, 4).getFloat32(0, true);
  }
  skip(wireType) {
    if (wireType === 0) this.readVarint();
    else if (wireType === 2) {
      const len = this.readVarint();
      this.pos += len;
    } else if (wireType === 5) this.pos += 4;
    else if (wireType === 1) this.pos += 8;
    else throw new Error(`unsupported protobuf wireType ${wireType} at pos ${this.pos}`);
  }
}

function decodePriceItem(bytes) {
  const r = new ProtoReader(bytes);
  const item = { id: "", price: 0 };
  while (!r.eof()) {
    const { field, wireType } = r.readTag();
    if (field === 1 && wireType === 2) item.id = r.readString(r.readVarint());
    else if (field === 3 && wireType === 5) item.price = r.readFloatLE();
    else r.skip(wireType);
  }
  return item;
}

function decodePriceResponse(bytes) {
  const r = new ProtoReader(bytes);
  const resp = { seasonId: "", mode: "", items: [] };
  while (!r.eof()) {
    const { field, wireType } = r.readTag();
    if (field === 1 && wireType === 2) resp.seasonId = r.readString(r.readVarint());
    else if (field === 2 && wireType === 2) resp.items.push(decodePriceItem(r.readBytes(r.readVarint())));
    else if (field === 3 && wireType === 2) resp.mode = r.readString(r.readVarint());
    else r.skip(wireType);
  }
  return resp;
}

async function fetchSeason(seasonId) {
  const url = `https://etor-zero.981001.xyz/etor-api/api/prices-snapshot/${seasonId}?format=protobuf`;
  const res = await fetch(url);
  if (!res.ok) return null;
  return decodePriceResponse(new Uint8Array(await res.arrayBuffer()));
}

function isValid(parsed) {
  if (!parsed || parsed.items.length <= 1) return false;
  return parsed.items.filter((it) => it.price > 0).length > 1;
}

async function findHardcoreSeason() {
  let best = null;
  for (let s = HARDCORE_START_SEASON; s <= MAX_SEASON; s += SEASON_STEP) {
    const parsed = await fetchSeason(s);
    if (!isValid(parsed)) break;
    best = { seasonId: s, parsed };
  }
  if (!best) {
    for (let s = HARDCORE_START_SEASON - SEASON_STEP; s >= MIN_SEASON; s -= SEASON_STEP) {
      const parsed = await fetchSeason(s);
      if (isValid(parsed)) {
        best = { seasonId: s, parsed };
        break;
      }
    }
  }
  if (!best) throw new Error("no valid hardcore season found");
  return best;
}

async function main() {
  console.log("하드코어 시즌 탐색 중...");
  const { seasonId, parsed } = await findHardcoreSeason();
  console.log(`선택된 시즌: ${seasonId} (아이템 ${parsed.items.length}개)`);

  const names = JSON.parse(fs.readFileSync(NAMES_PATH, "utf8"));

  const out = {};
  let matched = 0, unmatched = 0;
  for (const item of parsed.items) {
    const n = names[item.id];
    if (n) matched++;
    else unmatched++;
    out[item.id] = { name: n?.name || "", type: n?.type || "", price: item.price };
  }
  if (out["100300"]) out["100300"].price = 1.0; // 최초의 불꽃 결정 = 가치 기준 단위

  console.log(`이름 매칭: ${matched} / 매칭 안 됨(신규·하드코어 전용 추정): ${unmatched}`);

  fs.mkdirSync(path.dirname(OUT_PATH), { recursive: true });
  fs.writeFileSync(OUT_PATH, JSON.stringify(out, null, 2) + "\n");
  console.log(`작성됨: ${OUT_PATH}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
