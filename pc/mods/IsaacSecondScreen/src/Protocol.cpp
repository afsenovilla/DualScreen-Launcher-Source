#include "Protocol.h"

#include <cerrno>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <limits>
#include <utility>

namespace isaacds {
namespace {

constexpr size_t kMaximumPacketBytes = 65507;
constexpr size_t kMaximumFields = 32;
constexpr size_t kMaximumRooms = 169;
constexpr size_t kMaximumCollectibles = 768;
constexpr size_t kMaximumActives = 4;
constexpr size_t kMaximumTrinkets = 2;
constexpr size_t kMaximumPockets = 2;
constexpr size_t kMaximumTransformations = 32;

std::vector<std::string_view> Split(std::string_view input, char separator,
                                    size_t maximum) {
  std::vector<std::string_view> result;
  result.reserve(maximum);
  size_t start = 0;
  for (;;) {
    if (result.size() >= maximum) return {};
    const size_t end = input.find(separator, start);
    if (end == std::string_view::npos) {
      result.emplace_back(input.substr(start));
      return result;
    }
    result.emplace_back(input.substr(start, end - start));
    start = end + 1;
  }
}

bool ParseSigned(std::string_view input, int64_t minimum, int64_t maximum,
                 int64_t* output) {
  if (!output || input.empty() || input.size() >= 48) return false;
  char buffer[48] = {};
  std::memcpy(buffer, input.data(), input.size());
  char* end = nullptr;
  errno = 0;
  const long long value = std::strtoll(buffer, &end, 10);
  if (errno == ERANGE || end != buffer + input.size() || value < minimum ||
      value > maximum)
    return false;
  *output = static_cast<int64_t>(value);
  return true;
}

bool ParseUnsigned(std::string_view input, uint64_t maximum,
                   uint64_t* output) {
  if (!output || input.empty() || input.size() >= 48 || input.front() == '-')
    return false;
  char buffer[48] = {};
  std::memcpy(buffer, input.data(), input.size());
  char* end = nullptr;
  errno = 0;
  const unsigned long long value = std::strtoull(buffer, &end, 10);
  if (errno == ERANGE || end != buffer + input.size() || value > maximum)
    return false;
  *output = static_cast<uint64_t>(value);
  return true;
}

bool ParseInt(std::string_view input, int minimum, int maximum, int* output) {
  int64_t value = 0;
  if (!ParseSigned(input, minimum, maximum, &value) || !output) return false;
  *output = static_cast<int>(value);
  return true;
}

bool ParseDouble(std::string_view input, double minimum, double maximum,
                 double* output) {
  if (!output || input.empty() || input.size() >= 64) return false;
  char buffer[64] = {};
  std::memcpy(buffer, input.data(), input.size());
  char* end = nullptr;
  errno = 0;
  const double value = std::strtod(buffer, &end);
  if (errno == ERANGE || end != buffer + input.size() ||
      !std::isfinite(value) || value < minimum || value > maximum)
    return false;
  *output = value;
  return true;
}

int Hex(char value) {
  if (value >= '0' && value <= '9') return value - '0';
  if (value >= 'a' && value <= 'f') return value - 'a' + 10;
  if (value >= 'A' && value <= 'F') return value - 'A' + 10;
  return -1;
}

bool ParseRecordCount(std::string_view input, size_t maximum,
                      size_t* output) {
  uint64_t value = 0;
  if (!output || !ParseUnsigned(input, maximum, &value)) return false;
  *output = static_cast<size_t>(value);
  return true;
}

bool ParseStatus(const std::vector<std::string_view>& fields,
                 LiveState* state) {
  if (fields.size() != 25) return false;
  int running = 0;
  int character_id = 0;
  std::string character_name;
  Consumables consumables;
  Health health;
  Stats stats;
  uint64_t mask = 0;
  if (!ParseInt(fields[4], 0, 1, &running) ||
      !ParseInt(fields[5], 0, 255, &character_id) ||
      !PercentDecode(fields[6], &character_name) ||
      !ParseInt(fields[7], 0, 999, &consumables.coins) ||
      !ParseInt(fields[8], 0, 999, &consumables.bombs) ||
      !ParseInt(fields[9], 0, 999, &consumables.keys) ||
      !ParseInt(fields[10], 0, 192, &health.hearts) ||
      !ParseInt(fields[11], 0, 192, &health.max_hearts) ||
      !ParseInt(fields[12], 0, 192, &health.soul_hearts) ||
      !ParseUnsigned(fields[13], std::numeric_limits<uint64_t>::max(), &mask) ||
      !ParseInt(fields[14], 0, 32, &health.eternal_hearts) ||
      !ParseInt(fields[15], 0, 32, &health.golden_hearts) ||
      !ParseInt(fields[16], 0, 32, &health.bone_hearts) ||
      !ParseInt(fields[17], 0, 96, &health.rotten_hearts) ||
      !ParseInt(fields[18], 0, 32, &health.broken_hearts) ||
      !ParseDouble(fields[19], -100000.0, 100000.0, &stats.damage) ||
      !ParseDouble(fields[20], -100000.0, 100000.0, &stats.tears) ||
      !ParseDouble(fields[21], -100000.0, 100000.0, &stats.range) ||
      !ParseDouble(fields[22], -100000.0, 100000.0, &stats.shot_speed) ||
      !ParseDouble(fields[23], -100000.0, 100000.0, &stats.move_speed) ||
      !ParseDouble(fields[24], -100000.0, 100000.0, &stats.luck))
    return false;
  health.black_heart_mask = mask;
  state->in_run = running != 0;
  state->character_id = character_id;
  state->character_name = std::move(character_name);
  state->consumables = consumables;
  state->health = health;
  state->stats = stats;
  return true;
}

bool ParseMap(const std::vector<std::string_view>& fields, LiveState* state) {
  if (fields.size() != 12) return false;
  MapState next;
  uint64_t seed = 0;
  size_t count = 0;
  if (!PercentDecode(fields[4], &next.floor) ||
      !ParseInt(fields[5], 0, 64, &next.stage) ||
      !ParseInt(fields[6], 0, 64, &next.stage_type) ||
      !ParseInt(fields[7], 0, std::numeric_limits<int>::max(), &next.curses) ||
      !ParseInt(fields[8], -1, 255, &next.current_grid) ||
      !ParseUnsigned(fields[9], std::numeric_limits<uint32_t>::max(), &seed) ||
      !ParseRecordCount(fields[10], kMaximumRooms, &count))
    return false;
  next.dungeon_seed = static_cast<uint32_t>(seed);
  const auto records = fields[11].empty()
                           ? std::vector<std::string_view>()
                           : Split(fields[11], ';', kMaximumRooms + 1);
  if (records.size() != count) return false;
  next.rooms.reserve(count);
  for (const std::string_view record : records) {
    const auto values = Split(record, ',', 9);
    Room room;
    if ((values.size() != 7 && values.size() != 8) || !ParseInt(values[0], -1, 255, &room.grid) ||
        !ParseInt(values[1], -1, 255, &room.safe_grid) ||
        !ParseInt(values[2], 0, std::numeric_limits<int>::max(),
                  &room.display_flags) ||
        !ParseInt(values[3], 0, 1000000, &room.visited) ||
        !ParseInt(values[4], 0, 255, &room.type) ||
        !ParseInt(values[5], 0, 16, &room.shape) ||
        !ParseInt(values[6], 0, 65535, &room.subtype))
      return false;
    if (values.size() == 8 && !ParseInt(values[7], 0, std::numeric_limits<int>::max(), &room.flags)) return false;
    // Defense in depth: a malformed or modified state provider still cannot
    // disclose a descriptor the game has not marked for display.
    if (room.display_flags > 0 && room.grid >= 0) next.rooms.push_back(room);
  }
  next.received = true;
  state->map = std::move(next);
  return true;
}

bool ParseInventory(const std::vector<std::string_view>& fields,
                    LiveState* state) {
  const bool capacity_format = fields.size() == 15;
  if (!capacity_format && fields.size() != 12) return false;
  InventoryState next;
  size_t collectible_count = 0;
  size_t active_count = 0;
  size_t trinket_count = 0;
  size_t pocket_count = 0;
  const size_t active_count_index = capacity_format ? 7 : 6;
  const size_t active_records_index = capacity_format ? 8 : 7;
  const size_t trinket_count_index = capacity_format ? 10 : 8;
  const size_t trinket_records_index = capacity_format ? 11 : 9;
  const size_t pocket_count_index = capacity_format ? 13 : 10;
  const size_t pocket_records_index = capacity_format ? 14 : 11;
  if (!ParseRecordCount(fields[4], kMaximumCollectibles,
                        &collectible_count) ||
      !ParseRecordCount(fields[active_count_index], kMaximumActives,
                        &active_count) ||
      !ParseRecordCount(fields[trinket_count_index], kMaximumTrinkets,
                        &trinket_count) ||
      !ParseRecordCount(fields[pocket_count_index], kMaximumPockets,
                        &pocket_count))
    return false;
  if (capacity_format &&
      (!ParseInt(fields[6], 1, 2, &next.active_capacity) ||
       !ParseInt(fields[9], 1, 2, &next.trinket_capacity) ||
       !ParseInt(fields[12], 1, 2, &next.pocket_capacity)))
    return false;

  const auto collectibles = fields[5].empty()
                                ? std::vector<std::string_view>()
                                : Split(fields[5], ';',
                                        kMaximumCollectibles + 1);
  const auto actives = fields[active_records_index].empty()
                           ? std::vector<std::string_view>()
                           : Split(fields[active_records_index], ';',
                                   kMaximumActives + 1);
  const auto trinkets = fields[trinket_records_index].empty()
                            ? std::vector<std::string_view>()
                            : Split(fields[trinket_records_index], ';',
                                    kMaximumTrinkets + 1);
  const auto pockets = fields[pocket_records_index].empty()
                           ? std::vector<std::string_view>()
                           : Split(fields[pocket_records_index], ';',
                                   kMaximumPockets + 1);
  if (collectibles.size() != collectible_count ||
      actives.size() != active_count || trinkets.size() != trinket_count ||
      pockets.size() != pocket_count)
    return false;

  next.collectibles.reserve(collectible_count);
  for (const std::string_view record : collectibles) {
    const auto values = Split(record, ',', 6);
    CollectedItem item;
    if (values.size() != 5 || !ParseInt(values[0], 1, 65535, &item.id) ||
        !ParseInt(values[1], 1, 999, &item.count) ||
        !PercentDecode(values[2], &item.name) ||
        !PercentDecode(values[3], &item.description) ||
        !ParseInt(values[4], -1, 4, &item.quality))
      return false;
    next.collectibles.push_back(std::move(item));
  }
  next.actives.reserve(active_count);
  for (const std::string_view record : actives) {
    const auto values = Split(record, ',', 5);
    ActiveItem item;
    if (values.size() != 4 || !ParseInt(values[0], 1, 65535, &item.id) ||
        !ParseInt(values[1], 0, 3, &item.slot) ||
        !ParseInt(values[2], 0, 1000000, &item.charge) ||
        !ParseInt(values[3], 0, 1000000, &item.battery_charge))
      return false;
    next.actives.push_back(item);
  }
  next.trinkets.reserve(trinket_count);
  for (const std::string_view record : trinkets) {
    const auto values = Split(record, ',', 4);
    Trinket item;
    int golden = 0;
    if (values.size() != 3 || !ParseInt(values[0], 1, 65535, &item.id) ||
        !ParseInt(values[1], 0, 1, &item.slot) ||
        !ParseInt(values[2], 0, 1, &golden))
      return false;
    item.golden = golden != 0;
    next.trinkets.push_back(item);
  }
  next.pockets.reserve(pocket_count);
  for (const std::string_view record : pockets) {
    const auto values = Split(record, ',', 7);
    PocketItem item;
    if (values.size() != 6 || (values[0] != "C" && values[0] != "P") ||
        !ParseInt(values[1], -1, 65535, &item.id) ||
        !ParseInt(values[2], 0, 65535, &item.color) ||
        !ParseInt(values[3], 0, 1, &item.slot) ||
        !PercentDecode(values[4], &item.name) ||
        !PercentDecode(values[5], &item.hud_animation))
      return false;
    item.kind = values[0] == "C" ? PocketKind::kCard : PocketKind::kPill;
    next.pockets.push_back(std::move(item));
  }
  next.received = true;
  state->inventory = std::move(next);
  return true;
}

bool ParseRun(const std::vector<std::string_view>& fields, LiveState* state) {
  const bool planetarium_format = fields.size() == 16;
  if (!planetarium_format && fields.size() != 15) return false;
  RunInfo next;
  size_t count = 0;
  const size_t devil_deals_index = planetarium_format ? 13 : 12;
  const size_t transformation_count_index = planetarium_format ? 14 : 13;
  const size_t transformation_records_index = planetarium_format ? 15 : 14;
  if (!PercentDecode(fields[4], &next.seed) ||
      !PercentDecode(fields[5], &next.floor) ||
      !ParseInt(fields[6], 0, 64, &next.stage) ||
      !ParseInt(fields[7], 0, 64, &next.stage_type) ||
      !ParseInt(fields[8], 0, std::numeric_limits<int>::max(), &next.curses) ||
      !ParseUnsigned(fields[9], std::numeric_limits<uint64_t>::max(),
                     &next.timer_frames) ||
      !ParseDouble(fields[10], 0.0, 1.0, &next.devil_chance) ||
      !ParseDouble(fields[11], 0.0, 1.0, &next.angel_chance) ||
      (planetarium_format &&
       !ParseDouble(fields[12], 0.0, 1.0, &next.planetarium_chance)) ||
      !ParseInt(fields[devil_deals_index], 0, 999, &next.devil_deals) ||
      !ParseRecordCount(fields[transformation_count_index],
                        kMaximumTransformations, &count))
    return false;
  const auto records = fields[transformation_records_index].empty()
                           ? std::vector<std::string_view>()
                           : Split(fields[transformation_records_index], ',',
                                   kMaximumTransformations + 1);
  if (records.size() != count) return false;
  next.transformations.reserve(count);
  for (const std::string_view record : records) {
    int value = 0;
    if (!ParseInt(record, 0, 255, &value)) return false;
    next.transformations.push_back(value);
  }
  next.received = true;
  state->run = std::move(next);
  return true;
}

bool ParseNativeMap(const std::vector<std::string_view>& fields,LiveState* state) {
  if(fields.size()!=6) return false;
  size_t count=0;
  if(!ParseRecordCount(fields[4],2048,&count)) return false;
  const auto records=fields[5].empty() ? std::vector<std::string_view>() : Split(fields[5],';',2049);
  if(records.size()!=count) return false;
  NativeMapState next;
  for(auto record:records) {
    const auto f=Split(record,',',12);
    MapDrawCommand c;
    if(f.size()!=11 || !ParseInt(f[0],0,2,&c.actor) || !PercentDecode(f[1],&c.animation) ||
       c.animation.empty() || c.animation.size()>64 || !ParseInt(f[2],0,127,&c.frame) ||
       !ParseDouble(f[3],-4096,4096,&c.x) || !ParseDouble(f[4],-4096,4096,&c.y) ||
       !ParseDouble(f[5],0.001,100,&c.scale_x) || !ParseDouble(f[6],0.001,100,&c.scale_y) ||
       !ParseDouble(f[7],0,1,&c.red) || !ParseDouble(f[8],0,1,&c.green) ||
       !ParseDouble(f[9],0,1,&c.blue) || !ParseDouble(f[10],0,1,&c.alpha)) return false;
    next.commands.push_back(std::move(c));
  }
  next.received=true;state->native_map=std::move(next);return true;
}

bool ParseProgress(const std::vector<std::string_view>& fields, LiveState* state) {
  if (fields.size() != 12 && fields.size() != 13) return false;
  ProgressState next;
  auto bits = [](std::string_view value, size_t count, std::string& out) {
    if (value.size() != count || value.find_first_not_of("01") != std::string_view::npos) return false;
    out.assign(value); return true;
  };
  int readonly = 0;
  if (!bits(fields[5],642,next.achievements) || !bits(fields[6],46,next.challenges) ||
      !bits(fields[8],104,next.bosses) || !ParseInt(fields[9],0,1,&readonly) ||
      !ParseInt(fields[10],0,3,&next.save_slot) ||
      !ParseInt(fields[11],-1,100000000,&next.total_hits)) return false;
  next.readonly = readonly != 0;
  const auto history = fields[4].empty() ? std::vector<std::string_view>() : Split(fields[4],';',4097);
  if (history.size()>4096) return false;
  int previous = -1;
  for (const auto record : history) {
    const auto pair = Split(record,',',3);
    PickupHistoryEntry entry;
    if (pair.size()!=2 || !ParseInt(pair[0],1,65535,&entry.id) ||
        !ParseInt(pair[1],0,2147483647,&entry.time) || entry.time < previous) return false;
    previous=entry.time; next.history.push_back(entry);
    CollectedItem pickup; pickup.id=entry.id; pickup.count=1; next.pickups.push_back(std::move(pickup));
  }
  const auto events=Split(fields[7],',',524);
  if(events.size()!=523) return false;
  for (const auto record : events) {
    int value=0;
    if(!ParseInt(record,std::numeric_limits<int>::min(),std::numeric_limits<int>::max(),&value)) return false;
    next.events.push_back(value);
  }
  if(fields.size()==13) {
    const auto marks=Split(fields[12],',',493);
    if(marks.size()!=492) return false;
    for(auto record:marks) { int value=0; if(!ParseInt(record,0,15,&value)) return false; next.completion.push_back(value); }
  }
  next.received=true; state->progress=std::move(next); return true;
}

bool ParseDescriptions(const std::vector<std::string_view>& fields,
                       LiveState* state) {
  constexpr size_t kMaximumDescriptions = 160;
  size_t count = 0;
  if (fields.size() != 6 ||
      !ParseRecordCount(fields[4], kMaximumDescriptions, &count))
    return false;
  DescriptionState next;
  const auto records = count == 0 ? std::vector<std::string_view>{}
                                  : Split(fields[5], ';', kMaximumDescriptions + 1);
  if (records.size() != count) return false;
  for (const auto record : records) {
    const auto values = Split(record, ',', 5);
    if (values.size() != 4 || values[0].empty() || values[0].size() > 8) return false;
    const char kind = values[0].front();
    if (kind != 'c' && kind != 't' && kind != 'k' && kind != 'p') return false;
    Description entry;
    if (!PercentDecode(values[1], &entry.name) ||
        !PercentDecode(values[2], &entry.text))
      return false;
    entry.from_eid = values[3] == "eid";
    next.entries[std::string(values[0])] = std::move(entry);
  }
  next.received = true;
  state->descriptions = std::move(next);
  return true;
}

}  // namespace

bool PercentDecode(std::string_view input, std::string* output) {
  if (!output || input.size() > 4096) return false;
  std::string decoded;
  decoded.reserve(input.size());
  for (size_t index = 0; index < input.size(); ++index) {
    const unsigned char value = static_cast<unsigned char>(input[index]);
    if (value == '%') {
      if (index + 2 >= input.size()) return false;
      const int high = Hex(input[index + 1]);
      const int low = Hex(input[index + 2]);
      if (high < 0 || low < 0) return false;
      const char byte = static_cast<char>((high << 4) | low);
      if (byte == '\0') return false;
      decoded.push_back(byte);
      index += 2;
    } else {
      if (value < 0x20 || value == 0x7F) return false;
      decoded.push_back(static_cast<char>(value));
    }
  }
  *output = std::move(decoded);
  return true;
}

bool ApplyStatePacket(const char* bytes, size_t size, LiveState* state,
                      PacketTopic* topic) {
  if (topic) *topic = PacketTopic::kNone;
  if (!bytes || !state || !size || size > kMaximumPacketBytes ||
      std::memchr(bytes, '\0', size))
    return false;
  const auto fields = Split(std::string_view(bytes, size), '\t',
                            kMaximumFields);
  if (fields.size() < 4 || fields[0] != "DSI1") return false;
  uint64_t sequence = 0;
  uint64_t frame = 0;
  if (!ParseUnsigned(fields[2], std::numeric_limits<uint64_t>::max(),
                      &sequence) ||
      !ParseUnsigned(fields[3], std::numeric_limits<uint64_t>::max(), &frame))
    return false;
  // Isaac can reload the mod's Lua state without restarting the process. The
  // producer sequence consequently starts at one again while the native panel
  // is still alive. Treat that first packet as a new authoritative stream.
  const bool restart = sequence <= state->last_sequence;
  if (restart && sequence != 1) return false;
  LiveState restarted;
  LiveState* destination = state;
  if (restart) state = &restarted;

  PacketTopic parsed = PacketTopic::kNone;
  bool ok = false;
  if (fields[1] == "S") {
    ok = ParseStatus(fields, state);
    parsed = PacketTopic::kStatus;
  } else if (fields[1] == "M") {
    ok = ParseMap(fields, state);
    parsed = PacketTopic::kMap;
  } else if (fields[1] == "I") {
    ok = ParseInventory(fields, state);
    parsed = PacketTopic::kInventory;
  } else if (fields[1] == "R") {
    ok = ParseRun(fields, state);
    parsed = PacketTopic::kRun;
  }
  if (fields[1] == "D") {
    ok = ParseNativeMap(fields,state); parsed=PacketTopic::kNativeMap;
  }
  if (fields[1] == "P") {
    ok = ParseProgress(fields, state);
    parsed = PacketTopic::kProgress;
  }
  if (fields[1] == "E") {
    ok = ParseDescriptions(fields, state);
    parsed = PacketTopic::kDescriptions;
  }
  if (!ok) return false;
  state->last_sequence = sequence;
  state->game_frame = frame;
  state->last_packet_at = GetTickCount64();
  if (restart) *destination = std::move(restarted);
  if (topic) *topic = parsed;
  return true;
}

}  // namespace isaacds
