local DualScreenIsaac = RegisterMod("Dual Screen Isaac", 1)
local game = Game()
local itemConfig = Isaac.GetItemConfig()

-- The executable-side winmm proxy loads the actual x86 patch before this mod.
-- Lua remains authoritative for run state and publishes bounded snapshots
-- directly into the already-loaded in-process native lower-display patch.

local nativeBridge = nil
local sequence = 0
local bridgeWarningShown = false
local MAX_COLLECTIBLE_ID = 732 -- Exact maximum in v1.9.7.17.J460 items.xml.
local MAX_PLAYER_FORM = 14

local function debug(message)
    Isaac.DebugString("[DualScreenIsaac] " .. tostring(message))
end

debug("state bridge initialized for the native winmm proxy")

local function safe(defaultValue, callback)
    local ok, value = pcall(callback)
    if ok and value ~= nil then
        return value
    end
    return defaultValue
end

local function encode(value)
    local text = tostring(value or "")
    return (text:gsub("[^A-Za-z0-9_.~-]", function(character)
        return string.format("%%%02X", string.byte(character))
    end))
end

local function number(value, fallback)
    local numeric = tonumber(value)
    if numeric == nil or numeric ~= numeric or numeric == math.huge or
       numeric == -math.huge then
        numeric = fallback or 0
    end
    return tostring(numeric)
end

local function openBridge()
    if nativeBridge ~= nil then
        return true
    end
    if type(IsaacSecondScreenSend) == "function" then
        nativeBridge = { send = IsaacSecondScreenSend }
        debug("authoritative native state bridge ready")
        return true
    end
    if package == nil or type(package.loadlib) ~= "function" then
        if not bridgeWarningShown then
            bridgeWarningShown = true
            debug("native state-submit global is unavailable")
        end
        return false
    end
    local loader, loadError = package.loadlib(
        "winmm.dll", "luaopen_isaac_second_screen")
    if type(loader) ~= "function" then
        if not bridgeWarningShown then
            bridgeWarningShown = true
            debug("native state API unavailable: " .. tostring(loadError))
        end
        return false
    end
    local ok, bridge = pcall(loader)
    if not ok or type(bridge) ~= "table" or type(bridge.send) ~= "function" then
        if not bridgeWarningShown then
            bridgeWarningShown = true
            debug("native state API initialization failed: " .. tostring(bridge))
        end
        return false
    end
    nativeBridge = bridge
    debug("authoritative direct in-process state bridge ready")
    return true
end

local function send(topic, fields)
    if not openBridge() then
        return
    end
    sequence = sequence + 1
    local packet = {
        "DSI1",
        topic,
        tostring(sequence),
        tostring(game:GetFrameCount()),
    }
    for index = 1, #fields do
        packet[#packet + 1] = tostring(fields[index] or "")
    end
    local payload = table.concat(packet, "\t")
    local callOk, accepted = pcall(nativeBridge.send, payload)
    if (not callOk or not accepted) and not bridgeWarningShown then
        bridgeWarningShown = true
        debug("direct state submit failed: " .. tostring(accepted))
    end
end

local function getPlayer()
    if safe(0, function() return game:GetNumPlayers() end) < 1 then
        return nil
    end
    return Isaac.GetPlayer(0)
end

local function sendNoRun()
    send("S", {
        0, 0, encode("NO ACTIVE RUN"),
        0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0,
    })
end

local function sendStatus(player)
    local maxFireDelay = safe(0, function() return player.MaxFireDelay end)
    local tearRange = safe(0, function() return player.TearRange end)
    if tearRange == 0 then
        tearRange = -safe(0, function() return player.TearHeight end) * 40
    end
    send("S", {
        1,
        safe(0, function() return player:GetPlayerType() end),
        encode(safe("Unknown", function() return player:GetName() end)),
        safe(0, function() return player:GetNumCoins() end),
        safe(0, function() return player:GetNumBombs() end),
        safe(0, function() return player:GetNumKeys() end),
        safe(0, function() return player:GetHearts() end),
        safe(0, function() return player:GetMaxHearts() end),
        safe(0, function() return player:GetSoulHearts() end),
        safe(0, function() return player:GetBlackHearts() end),
        safe(0, function() return player:GetEternalHearts() end),
        safe(0, function() return player:GetGoldenHearts() end),
        safe(0, function() return player:GetBoneHearts() end),
        safe(0, function() return player:GetRottenHearts() end),
        safe(0, function() return player:GetBrokenHearts() end),
        number(safe(0, function() return player.Damage end)),
        number(30 / (maxFireDelay + 1)),
        number(tearRange / 40),
        number(safe(0, function() return player.ShotSpeed end)),
        number(safe(0, function() return player.MoveSpeed end)),
        number(safe(0, function() return player.Luck end)),
    })
end

local function joinRecords(records)
    return table.concat(records, ";")
end

-- Full "what does it do" text for everything the player holds. External Item
-- Descriptions (EID) is used when it is installed; otherwise the game's own
-- pickup line is the fallback. Sent only when the held set changes.
local descriptionCache = {}
local lastDescriptionSignature = nil
local MAX_DESCRIPTION_BYTES = 420
local MAX_DESCRIPTION_PACKET = 56000

local function cleanDescription(text)
    text = tostring(text or "")
    text = text:gsub("\226\134\145", "+")   -- up arrow
    text = text:gsub("\226\134\147", "-")   -- down arrow
    text = text:gsub("\226\134\146", ">")   -- right arrow
    text = text:gsub("{{[^}]*}}", "")
    text = text:gsub("\r", "")
    text = text:gsub("%s*#%s*", "\n")
    text = text:gsub("[ \t]+", " ")
    text = text:gsub("\n+", "\n")
    text = text:gsub("^%s+", ""):gsub("%s+$", "")
    if #text > MAX_DESCRIPTION_BYTES then
        text = text:sub(1, MAX_DESCRIPTION_BYTES)
        -- Never cut a UTF-8 sequence in half.
        while #text > 0 do
            local last = text:byte(#text)
            if last < 0x80 then break end
            if last >= 0xC0 then text = text:sub(1, #text - 1) break end
            text = text:sub(1, #text - 1)
        end
        text = text .. "..."
    end
    return text
end

local function describe(key, variant, subtype, fallbackName, fallbackText)
    local cached = descriptionCache[key]
    if cached ~= nil then return cached end
    local name, text = fallbackName or "", fallbackText or ""
    local source = "game"
    if type(EID) == "table" and type(EID.getDescriptionObj) == "function" then
        local ok, object = pcall(function()
            return EID:getDescriptionObj(5, variant, subtype, nil, false)
        end)
        if ok and type(object) == "table" and type(object.Description) == "string" and
           object.Description ~= "" then
            text = object.Description
            if type(object.Name) == "string" and object.Name ~= "" then name = object.Name end
            source = "eid"
        end
    end
    cached = { name = cleanDescription(name), text = cleanDescription(text), source = source }
    descriptionCache[key] = cached
    return cached
end

local function sendDescriptions(player)
    -- Spanish descriptions on both screens, whatever the game language is.
    if type(EID) == "table" and type(EID.Config) == "table" and
       type(EID.descriptions) == "table" and EID.descriptions["spa"] ~= nil and
       EID.Config["Language"] ~= "spa" then
        EID.Config["Language"] = "spa"
        descriptionCache = {}
    end
    local entries, keys, total = {}, {}, 0
    local function add(key, variant, subtype, name, text)
        if #entries >= 160 then return end
        if keys[key] then return end
        keys[key] = true
        local described = describe(key, variant, subtype, name, text)
        local record = table.concat({
            key, encode(described.name), encode(described.text), described.source,
        }, ",")
        -- Stay inside one UDP-sized packet; held trinkets/pockets go first.
        if total + #record + 1 > MAX_DESCRIPTION_PACKET then return end
        total = total + #record + 1
        entries[#entries + 1] = record
    end
    for slot = 0, 1 do
        local encodedId = safe(0, function() return player:GetTrinket(slot) end)
        if encodedId > 0 then
            local id = encodedId % 32768
            local config = safe(nil, function() return itemConfig:GetTrinket(id) end)
            add("t" .. id, 350, encodedId,
                safe("", function() return config.Name end),
                safe("", function() return config.Description end))
        end
        local cardId = safe(0, function() return player:GetCard(slot) end)
        if cardId > 0 then
            local card = safe(nil, function() return itemConfig:GetCard(cardId) end)
            add("k" .. cardId, 300, cardId,
                safe("", function() return card.Name end),
                safe("", function() return card.Description end))
        else
            local pillColor = safe(0, function() return player:GetPill(slot) end)
            if pillColor > 0 then
                local effect = safe(-1, function()
                    return game:GetItemPool():GetPillEffect(pillColor, player)
                end)
                local identified = safe(false, function()
                    return game:GetItemPool():IsPillIdentified(pillColor)
                end)
                local pill = safe(nil, function() return itemConfig:GetPillEffect(effect) end)
                if identified then
                    add("p" .. effect, 70, pillColor,
                        safe("", function() return pill.Name end), "")
                else
                    -- Unidentified pills keep their mystery, as in the game.
                    keys["p" .. effect] = true
                    entries[#entries + 1] = table.concat({
                        "p" .. effect, encode("???"), encode("Unidentified pill"), "game",
                    }, ",")
                end
            end
        end
    end
    for id = 1, MAX_COLLECTIBLE_ID do
        if safe(0, function() return player:GetCollectibleNum(id) end) > 0 then
            local config = safe(nil, function() return itemConfig:GetCollectible(id) end)
            add("c" .. id, 100, id,
                safe("", function() return config.Name end),
                safe("", function() return config.Description end))
        end
    end
    local signature = table.concat(entries, ";")
    if signature == lastDescriptionSignature then return end
    lastDescriptionSignature = signature
    send("E", { #entries, signature })
end

local function sendInventory(player)
    local collectibles = {}
    for id = 1, MAX_COLLECTIBLE_ID do
        local count = safe(0, function() return player:GetCollectibleNum(id) end)
        if count > 0 then
            local config = safe(nil, function() return itemConfig:GetCollectible(id) end)
            collectibles[#collectibles + 1] = table.concat({
                tostring(id),
                tostring(count),
                encode(safe("", function() return config.Name end)),
                encode(safe("", function() return config.Description end)),
                tostring(safe(-1, function() return config.Quality end)),
            }, ",")
        end
    end

    local actives = {}
    for slot = 0, 1 do
        local id = safe(0, function() return player:GetActiveItem(slot) end)
        if id > 0 then
            actives[#actives + 1] = table.concat({
                tostring(id),
                tostring(slot),
                tostring(safe(0, function() return player:GetActiveCharge(slot) end)),
                tostring(safe(0, function() return player:GetBatteryCharge(slot) end)),
            }, ",")
        end
    end

    local trinkets = {}
    for slot = 0, 1 do
        local encodedId = safe(0, function() return player:GetTrinket(slot) end)
        if encodedId > 0 then
            trinkets[#trinkets + 1] = table.concat({
                tostring(encodedId % 32768),
                tostring(slot),
                encodedId >= 32768 and "1" or "0",
            }, ",")
        end
    end

    local pockets = {}
    for slot = 0, 1 do
        local cardId = safe(0, function() return player:GetCard(slot) end)
        if cardId > 0 then
            local card = safe(nil, function() return itemConfig:GetCard(cardId) end)
            pockets[#pockets + 1] = table.concat({
                "C", tostring(cardId), "0", tostring(slot),
                encode(safe("Unknown Card", function() return card.Name end)),
                encode(safe("", function() return card.HudAnim end)),
            }, ",")
        else
            local pillColor = safe(0, function() return player:GetPill(slot) end)
            if pillColor > 0 then
                local effect = safe(-1, function()
                    return game:GetItemPool():GetPillEffect(pillColor, player)
                end)
                local pill = safe(nil, function() return itemConfig:GetPillEffect(effect) end)
                pockets[#pockets + 1] = table.concat({
                    "P", tostring(effect), tostring(pillColor), tostring(slot),
                    encode(safe("Unknown Pill", function() return pill.Name end)),
                    encode(""),
                }, ",")
            end
        end
    end

    -- A granted carrying capacity does not reserve an empty block of UI. The
    -- second slot is published only after it actually contains an item.
    local activeCapacity = #actives > 1 and 2 or 1
    local trinketCapacity = #trinkets > 1 and 2 or 1
    local pocketCapacity = #pockets > 1 and 2 or 1

    send("I", {
        #collectibles, joinRecords(collectibles),
        activeCapacity, #actives, joinRecords(actives),
        trinketCapacity, #trinkets, joinRecords(trinkets),
        pocketCapacity, #pockets, joinRecords(pockets),
    })
end

local function anyPlayerHasCollectible(collectible)
    if collectible == nil then
        return false
    end
    for index = 0, safe(0, function() return game:GetNumPlayers() end) - 1 do
        local player = Isaac.GetPlayer(index)
        if player ~= nil and safe(false, function()
            return player:HasCollectible(collectible, false)
        end) then
            return true
        end
    end
    return false
end

local function anyPlayerHasTrinket(trinket)
    if trinket == nil then
        return false
    end
    for index = 0, safe(0, function() return game:GetNumPlayers() end) - 1 do
        local player = Isaac.GetPlayer(index)
        if player ~= nil and safe(false, function()
            return player:HasTrinket(trinket, false)
        end) then
            return true
        end
    end
    return false
end

local function getDealChances()
    local level = game:GetLevel()
    local totalChance = math.max(0, math.min(1,
        safe(0, function() return game:GetRoom():GetDevilRoomChance() end)))
    local angelSpawned = safe(false, function()
        return game:GetStateFlag(GameStateFlag.STATE_FAMINE_SPAWNED)
    end)
    local devilSpawned = safe(false, function()
        return game:GetStateFlag(GameStateFlag.STATE_DEVILROOM_SPAWNED)
    end)
    local devilVisited = safe(false, function()
        return game:GetStateFlag(GameStateFlag.STATE_DEVILROOM_VISITED)
    end)
    local angelModifier = safe(0, function()
        return level:GetAngelRoomChance()
    end)
    local hasVirtues = anyPlayerHasCollectible(
        CollectibleType.COLLECTIBLE_BOOK_OF_VIRTUES)
    local hasContrition = anyPlayerHasCollectible(
        CollectibleType.COLLECTIBLE_ACT_OF_CONTRITION)
    local devilShare = 1.0

    if anyPlayerHasCollectible(CollectibleType.COLLECTIBLE_EUCHARIST) then
        devilShare = 0.0
    elseif devilSpawned and devilVisited and
           safe(0, function() return game:GetDevilRoomDeals() end) > 0 then
        if hasVirtues or hasContrition or angelModifier > 0 then
            devilShare = 0.5
        end
    elseif devilSpawned or hasVirtues or angelModifier > 0 then
        if not devilVisited and not angelSpawned then
            devilShare = 0.0
        else
            devilShare = 0.5
        end
    end

    if devilShare == 0.5 then
        if anyPlayerHasTrinket(TrinketType.TRINKET_ROSARY_BEAD) then
            devilShare = devilShare * 0.5
        end
        if safe(0, function() return game:GetDonationModAngel() end) >= 10 then
            devilShare = devilShare * 0.5
        end
        if anyPlayerHasCollectible(CollectibleType.COLLECTIBLE_KEY_PIECE_1) then
            devilShare = devilShare * 0.75
        end
        if anyPlayerHasCollectible(CollectibleType.COLLECTIBLE_KEY_PIECE_2) then
            devilShare = devilShare * 0.75
        end
        if safe(false, function()
            return level:GetStateFlag(LevelStateFlag.STATE_EVIL_BUM_KILLED)
        end) then
            devilShare = devilShare * 0.75
        end
        local bumLeft = safe(false, function()
            return level:GetStateFlag(LevelStateFlag.STATE_BUM_LEFT)
        end)
        local evilBumLeft = safe(false, function()
            return level:GetStateFlag(LevelStateFlag.STATE_EVIL_BUM_LEFT)
        end)
        if bumLeft and not evilBumLeft then
            devilShare = devilShare * 0.9
        elseif evilBumLeft and not bumLeft then
            devilShare = devilShare * 1.1
        end
        if angelModifier > 0 or
           (angelModifier < 0 and (hasVirtues or hasContrition)) then
            devilShare = devilShare * (1.0 - angelModifier)
        end
        if hasVirtues then
            devilShare = devilShare * 0.75
        end
        devilShare = math.max(0, math.min(1, devilShare))
    end

    return totalChance * devilShare, totalChance * (1.0 - devilShare)
end

local function sendNativeMap()
    local snapshot = safe("", function() return IsaacSecondScreenNativeMap() end)
    local count, records = snapshot:match("^(%d+)|(.*)$")
    if count then send("D", { count, records }) end
end

local function sendMap()
    local level = game:GetLevel()
    local roomList = level:GetRooms()
    local rooms = {}
    local nativeFlags = {}
    local native = safe("", function() return IsaacSecondScreenRoomFlags() end)
    for listIndex, flags in native:gmatch("(%d+),(%d+)") do
        nativeFlags[tonumber(listIndex)] = tonumber(flags)
    end
    for index = 0, roomList.Size - 1 do
        local descriptor = roomList:Get(index)
        local displayFlags = safe(0, function() return descriptor.DisplayFlags end)
        -- The map deliberately uses the game's own knowledge flag. Descriptor
        -- existence alone is never enough to reveal a secret or undiscovered room.
        if displayFlags > 0 and nativeFlags[index] ~= nil then
            local data = safe(nil, function() return descriptor.Data end)
            rooms[#rooms + 1] = table.concat({
                tostring(safe(-1, function() return descriptor.GridIndex end)),
                tostring(safe(-1, function() return descriptor.SafeGridIndex end)),
                tostring(displayFlags),
                tostring(safe(0, function() return descriptor.VisitedCount end)),
                tostring(safe(1, function() return data.Type end)),
                tostring(safe(1, function() return data.Shape end)),
                tostring(safe(0, function() return data.Subtype end)),
                tostring(nativeFlags[index]),
            }, ",")
        end
    end
    send("M", {
        encode(safe("Unknown Floor", function() return level:GetName() end)),
        safe(0, function() return level:GetStage() end),
        safe(0, function() return level:GetStageType() end),
        safe(0, function() return level:GetCurses() end),
        safe(-1, function() return level:GetCurrentRoomIndex() end),
        safe(0, function() return level:GetDungeonPlacementSeed() end),
        #rooms,
        joinRecords(rooms),
    })
    sendNativeMap()
end

local function sendRun(player)
    local level = game:GetLevel()
    local devilChance, angelChance = getDealChances()
    local transformations = {}
    for form = 0, MAX_PLAYER_FORM do
        if safe(false, function() return player:HasPlayerForm(form) end) then
            transformations[#transformations + 1] = tostring(form)
        end
    end
    send("R", {
        encode(safe("", function() return game:GetSeeds():GetStartSeedString() end)),
        encode(safe("Unknown Floor", function() return level:GetName() end)),
        safe(0, function() return level:GetStage() end),
        safe(0, function() return level:GetStageType() end),
        safe(0, function() return level:GetCurses() end),
        safe(0, function() return game.TimeCounter end),
        number(devilChance),
        number(angelChance),
        number(safe(0, function() return level:GetPlanetariumChance() end)),
        safe(0, function() return game:GetDevilRoomDeals() end),
        #transformations,
        table.concat(transformations, ","),
    })
end

local pickupJournal = {}
local pickupSeen = {}
local jsonCodec = safe(nil, function() return require("json") end)

local function mergeHistory(history)
    local occurrences = {}
    for idText, timeText in history:gmatch("(%d+),(%d+)") do
        local id, time = tonumber(idText), tonumber(timeText)
        if id > 0 and id <= 65535 and time <= 2147483647 then
            local event = idText .. "," .. timeText
            occurrences[event] = (occurrences[event] or 0) + 1
            local key = event .. "," .. occurrences[event]
            if not pickupSeen[key] and #pickupJournal < 4096 then
                pickupSeen[key] = true
                pickupJournal[#pickupJournal + 1] = { id = id, time = time, key = key, order = #pickupJournal + 1 }
            end
        end
    end
    table.sort(pickupJournal, function(a, b)
        if a.time ~= b.time then return a.time < b.time end
        -- Keep the native order of acquisitions sharing a game tick.
        return a.order < b.order
    end)
    local records = {}
    for _, entry in ipairs(pickupJournal) do records[#records + 1] = entry.id .. "," .. entry.time end
    return table.concat(records, ";")
end

local function sendProgress()
    local snapshot = safe("", function() return IsaacSecondScreenNativeState() end)
    if snapshot == "" then return end
    local fields = {}
    for field in (snapshot .. "|"):gmatch("(.-)|") do fields[#fields + 1] = field end
    if #fields ~= 8 then return end
    fields[9] = fields[8]
    fields[1] = mergeHistory(fields[1])
    fields[8] = safe(-1, function() return IsaacSecondScreenHits(-1) end)
    send("P", fields)
end

local function saveHits()
    local hits = safe(-1, function() return IsaacSecondScreenHits(-1) end)
    if hits < 0 then return end
    local seed = safe("", function() return game:GetSeeds():GetStartSeedString() end)
    if seed ~= "" then
        if jsonCodec then
            DualScreenIsaac:SaveData(jsonCodec.encode({seed=seed,hits=hits,pickups=pickupJournal}))
        else
            DualScreenIsaac:SaveData(seed .. "|" .. tostring(hits))
        end
    end
end

function DualScreenIsaac:onUpdate()
    local frame = game:GetFrameCount()
    local player = getPlayer()
    if player == nil then
        if frame % 30 == 0 then
            sendNoRun()
        end
        return
    end
    if frame % 6 == 0 then
        sendStatus(player)
    end
    if frame % 15 == 0 then
        sendMap()
    end
    if frame % 30 == 0 then
        sendInventory(player)
        sendDescriptions(player)
        sendRun(player)
        sendProgress()
    end
    if frame % 300 == 0 then saveHits() end
end

function DualScreenIsaac:onNewRoom()
    local player = getPlayer()
    if player ~= nil then
        sendMap()
        sendInventory(player)
        sendDescriptions(player)
    end
end

function DualScreenIsaac:onNewLevel()
    local player = getPlayer()
    if player ~= nil then
        sendMap()
        sendRun(player)
    end
end

function DualScreenIsaac:onGameStarted(continued)
    bridgeWarningShown = false
    openBridge()
    local hits = 0
    pickupJournal = {}
    pickupSeen = {}
    descriptionCache = {}
    lastDescriptionSignature = nil
    if continued and self:HasData() then
        local data = self:LoadData()
        local seed, saved = data:match("^(.-)|(%d+)$")
        local decoded = safe(nil, function() return jsonCodec.decode(data) end)
        if type(decoded) == "table" and decoded.seed == game:GetSeeds():GetStartSeedString() then
            seed, saved = decoded.seed, decoded.hits
            local records = {}
            if type(decoded.pickups) == "table" then
                for index, entry in ipairs(decoded.pickups) do
                    if index > 4096 then break end
                    if type(entry) == "table" and type(entry.id) == "number" and type(entry.time) == "number" then
                        records[#records + 1] = tostring(entry.id) .. "," .. tostring(entry.time)
                    end
                end
            end
            mergeHistory(table.concat(records, ";"))
        end
        if seed == game:GetSeeds():GetStartSeedString() then hits = math.max(0, tonumber(saved) or 0) end
    end
    safe(-1, function() return IsaacSecondScreenHits(math.min(hits, 100000000)) end)
    local player = getPlayer()
    if player then
        sendStatus(player)
        sendMap()
        sendInventory(player)
        sendDescriptions(player)
        sendRun(player)
        sendProgress()
        saveHits()
    end
end

function DualScreenIsaac:onGameExit(shouldSave)
    if shouldSave then saveHits() end
    sendNoRun()
end

-- Establish the bridge as soon as the mod loads so the panel can distinguish
-- Isaac's menus from a missing publisher before the player enters a save.
if openBridge() then
    sendNoRun()
end

DualScreenIsaac:AddCallback(ModCallbacks.MC_POST_UPDATE, DualScreenIsaac.onUpdate)
DualScreenIsaac:AddCallback(ModCallbacks.MC_POST_NEW_ROOM, DualScreenIsaac.onNewRoom)
DualScreenIsaac:AddCallback(ModCallbacks.MC_POST_NEW_LEVEL, DualScreenIsaac.onNewLevel)
DualScreenIsaac:AddCallback(ModCallbacks.MC_POST_GAME_STARTED, DualScreenIsaac.onGameStarted)
DualScreenIsaac:AddCallback(ModCallbacks.MC_PRE_GAME_EXIT, DualScreenIsaac.onGameExit)
