#include "Panel.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windowsx.h>

#include <wtypes.h>
#include <gdiplus.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <deque>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "AssetCache.h"
#include "AssetCatalog.h"
#include "BitmapFont.h"
#include "Log.h"
#include "PanelInput.h"
#include "HudLayout.h"
#include "MapLayout.h"
#include "Protocol.h"

namespace isaacds {
namespace {

constexpr int kWidth = 1240;
constexpr int kHeight = 1080;
constexpr int kDashboardPageCount = 6;
constexpr int kItemColumns = 6;
constexpr int kItemVisibleRows = 2;
constexpr int kItemSlotSize = 86;
constexpr int kItemColumnStep = 126;
constexpr int kItemRowStep = 92;
constexpr int kItemGridLeft = 240;
constexpr int kItemGridTop = 590;
constexpr int kItemGridWidth =
    (kItemColumns - 1) * kItemColumnStep + kItemSlotSize;
constexpr int kItemGridHeight = kItemVisibleRows * kItemRowStep;
constexpr unsigned short kTouchPort = 7788;
constexpr unsigned short kStatePort = 7791;
constexpr UINT kTouchMessage = WM_APP + 0x4941;
constexpr UINT kStateMessage = WM_APP + 0x4942;
constexpr UINT kShutdownMessage = WM_APP + 0x4943;
constexpr UINT kDirectStateMessage = WM_APP + 0x4944;
constexpr UINT_PTR kRenderTimer = 1;
constexpr UINT_PTR kHealthTimer = 2;
constexpr ULONGLONG kMinimumFrameIntervalMs = 125;
constexpr ULONGLONG kStateStaleMs = 3000;

constexpr COLORREF kInk = RGB(52, 39, 29);
constexpr COLORREF kInkMuted = RGB(91, 69, 48);
constexpr COLORREF kWine = RGB(107, 36, 27);
constexpr COLORREF kBackground = RGB(23, 17, 14);
constexpr COLORREF kParchment = RGB(225, 201, 149);

enum class Page : uint8_t { kItems, kMap, kStats, kRunInfo, kPickups, kProgress };

struct Surface {
  HDC dc = nullptr;
  HBITMAP bitmap = nullptr;
  HGDIOBJ previous = nullptr;
  void* bits = nullptr;
};

struct MonitorChoice {
  RECT bounds = {0, 1080, kWidth, 1080 + kHeight};
  int score = -1;
};

HMODULE g_module = nullptr;
std::string g_build_label;
HANDLE g_thread = nullptr;
DWORD g_thread_id = 0;
HWND g_window = nullptr;
Surface g_back;
std::array<Surface, kDashboardPageCount> g_static_pages;
BitmapFont g_font;
SOCKET g_touch_socket = INVALID_SOCKET;
SOCKET g_state_socket = INVALID_SOCKET;
bool g_winsock = false;
LiveState g_state;
Page g_page = Page::kItems;
// Key of a held trinket/card/pill/active whose description is shown instead
// of the selected grid item (set by tapping the top HUD slots).
std::string g_focus_key;
std::vector<int> g_known_collectibles;
int g_selected_item = -1;
int g_progress_tab = 0;
int g_progress_offset = 0;
int g_progress_character = -1;
float g_item_scroll = 0.0f;
bool g_pointer_down = false;
bool g_pointer_scrolling = false;
float g_pointer_start_x = 0.0f;
float g_pointer_start_y = 0.0f;
float g_pointer_last_y = 0.0f;
ULONGLONG g_last_render_at = 0;
bool g_render_timer_armed = false;
bool g_logged_first_state = false;
bool g_was_connected = false;
SRWLOCK g_direct_state_lock = SRWLOCK_INIT;
std::deque<std::string> g_direct_state_packets;

RECT Rect(int left, int top, int right, int bottom) {
  RECT value = {left, top, right, bottom};
  return value;
}

void Fill(HDC dc, const RECT& rect, COLORREF color) {
  const COLORREF previous = SetDCBrushColor(dc, color);
  FillRect(dc, &rect, static_cast<HBRUSH>(GetStockObject(DC_BRUSH)));
  if (previous != CLR_INVALID) SetDCBrushColor(dc, previous);
}

void Frame(HDC dc, RECT rect, COLORREF color, int width = 2) {
  const COLORREF previous = SetDCBrushColor(dc, color);
  HBRUSH brush = static_cast<HBRUSH>(GetStockObject(DC_BRUSH));
  for (int inset = 0; inset < width; ++inset) {
    RECT edge = {rect.left + inset, rect.top + inset, rect.right - inset,
                 rect.bottom - inset};
    if (edge.left >= edge.right || edge.top >= edge.bottom) break;
    FrameRect(dc, &edge, brush);
  }
  if (previous != CLR_INVALID) SetDCBrushColor(dc, previous);
}

void AlphaFill(HDC dc, const RECT& rect, BYTE alpha, COLORREF color) {
  Gdiplus::Graphics graphics(dc);
  Gdiplus::SolidBrush brush(Gdiplus::Color(alpha, GetRValue(color),
                                           GetGValue(color), GetBValue(color)));
  graphics.FillRectangle(&brush, static_cast<INT>(rect.left),
                         static_cast<INT>(rect.top),
                         static_cast<INT>(rect.right - rect.left),
                         static_cast<INT>(rect.bottom - rect.top));
}

std::string Upper(std::string value) {
  for (char& character : value) {
    if (character >= 'a' && character <= 'z') character -= 'a' - 'A';
  }
  return value;
}

std::string Clean(const std::string& value, const std::string& fallback) {
  return value.empty() || value.front() == '#' ? fallback : value;
}

std::string Number(int value, int width = 0) {
  char result[32] = {};
  if (width > 0)
    std::snprintf(result, sizeof(result), "%0*d", width, value);
  else
    std::snprintf(result, sizeof(result), "%d", value);
  return result;
}

std::string Decimal(double value) {
  if (!std::isfinite(value)) return "--";
  char result[48] = {};
  std::snprintf(result, sizeof(result), "%.2f", value);
  return result;
}

std::string Percent(double value) {
  char result[48] = {};
  std::snprintf(result, sizeof(result), "%.1f%%",
                std::clamp(value * 100.0, 0.0, 100.0));
  return result;
}

bool Connected(ULONGLONG now = GetTickCount64()) {
  return g_state.last_packet_at && now - g_state.last_packet_at <= kStateStaleMs;
}

bool ItemPage() { return g_page == Page::kItems || g_page == Page::kPickups; }
const std::vector<CollectedItem>& DisplayItems() {
  return g_page == Page::kPickups ? g_state.progress.pickups : g_state.inventory.collectibles;
}

[[maybe_unused]] size_t PageIndex(Page page) {
  return static_cast<size_t>(page);
}

int ItemRows() {
  return static_cast<int>((DisplayItems().size() +
                           kItemColumns - 1) /
                          kItemColumns);
}

float MaximumItemScroll() {
  return static_cast<float>(
      std::max(0, ItemRows() * kItemRowStep - kItemGridHeight));
}

bool CreateSurface(HDC reference, Surface* surface) {
  if (!reference || !surface) return false;
  surface->dc = CreateCompatibleDC(reference);
  BITMAPINFO info = {};
  info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
  info.bmiHeader.biWidth = kWidth;
  info.bmiHeader.biHeight = -kHeight;
  info.bmiHeader.biPlanes = 1;
  info.bmiHeader.biBitCount = 32;
  info.bmiHeader.biCompression = BI_RGB;
  surface->bitmap = CreateDIBSection(reference, &info, DIB_RGB_COLORS,
                                     &surface->bits, nullptr, 0);
  if (!surface->dc || !surface->bitmap) return false;
  surface->previous = SelectObject(surface->dc, surface->bitmap);
  return surface->previous != nullptr && surface->previous != HGDI_ERROR;
}

void DestroySurface(Surface* surface) {
  if (!surface) return;
  if (surface->dc && surface->previous)
    SelectObject(surface->dc, surface->previous);
  if (surface->bitmap) DeleteObject(surface->bitmap);
  if (surface->dc) DeleteDC(surface->dc);
  *surface = {};
}

std::vector<Gdiplus::Point> RoughPanelPoints(const RECT& bounds, int seed,
                                             int offset_x = 0,
                                             int offset_y = 0) {
  const int left = bounds.left + offset_x;
  const int top = bounds.top + offset_y;
  const int right = bounds.right + offset_x;
  const int bottom = bounds.bottom + offset_y;
  constexpr int kPixel = 8;
  constexpr int kCorner = 16;
  const int depths[] = {0, 0, 8, 4, 0, 12, 4, -4, 0, 8, 0, 4};
  auto depth = [&](int index, int edge) {
    const unsigned mixed = static_cast<unsigned>(seed * 37 + edge * 71 +
                                                  index * 17);
    return depths[mixed % std::size(depths)];
  };

  // Every segment is axis-aligned and lands on an eight-pixel grid.  The
  // silhouette itself therefore looks torn out of a low-resolution sprite;
  // it is not a smooth polygon with square decorations placed on top.
  std::vector<Gdiplus::Point> points;
  points.reserve(static_cast<size_t>((right - left + bottom - top) /
                                     kPixel * 2 + 24));
  auto append = [&](int x, int y) {
    if (points.empty() || points.back().X != x || points.back().Y != y)
      points.push_back({x, y});
  };

  int x = left + kCorner;
  int y = top + depth(0, 0);
  append(x, y);
  int index = 1;
  for (; x < right - kCorner; ++index) {
    const int next_x = std::min(right - kCorner, x + kPixel);
    const int next_y = top + depth(index, 0);
    append(next_x, y);
    append(next_x, next_y);
    x = next_x;
    y = next_y;
  }

  append(right - kPixel, y);
  append(right - kPixel, top + kPixel);
  append(right, top + kCorner);
  x = right - depth(0, 1);
  y = top + kCorner;
  append(x, y);
  index = 1;
  for (; y < bottom - kCorner; ++index) {
    const int next_y = std::min(bottom - kCorner, y + kPixel);
    const int next_x = right - depth(index, 1);
    append(x, next_y);
    append(next_x, next_y);
    x = next_x;
    y = next_y;
  }

  append(x, bottom - kPixel);
  append(right - kPixel, bottom - kPixel);
  append(right - kCorner, bottom);
  x = right - kCorner;
  y = bottom - depth(0, 2);
  append(x, y);
  index = 1;
  for (; x > left + kCorner; ++index) {
    const int next_x = std::max(left + kCorner, x - kPixel);
    const int next_y = bottom - depth(index, 2);
    append(next_x, y);
    append(next_x, next_y);
    x = next_x;
    y = next_y;
  }

  append(left + kPixel, y);
  append(left + kPixel, bottom - kPixel);
  append(left, bottom - kCorner);
  x = left + depth(0, 3);
  y = bottom - kCorner;
  append(x, y);
  index = 1;
  for (; y > top + kCorner; ++index) {
    const int next_y = std::max(top + kCorner, y - kPixel);
    const int next_x = left + depth(index, 3);
    append(x, next_y);
    append(next_x, next_y);
    x = next_x;
    y = next_y;
  }

  append(x, top + kPixel);
  append(left + kPixel, top + kPixel);
  append(left + kCorner, top + depth(0, 0));
  return points;
}

void DrawAgedPanel(HDC dc, const RECT& bounds, int seed) {
  Gdiplus::Graphics graphics(dc);
  graphics.SetSmoothingMode(Gdiplus::SmoothingModeNone);
  const auto shadow_points = RoughPanelPoints(bounds, seed, 8, 10);
  Gdiplus::GraphicsPath shadow;
  shadow.AddPolygon(shadow_points.data(),
                    static_cast<INT>(shadow_points.size()));
  Gdiplus::SolidBrush shadow_brush(Gdiplus::Color(115, 7, 4, 3));
  graphics.FillPath(&shadow_brush, &shadow);

  const auto points = RoughPanelPoints(bounds, seed);
  Gdiplus::GraphicsPath panel;
  panel.AddPolygon(points.data(), static_cast<INT>(points.size()));
  Gdiplus::SolidBrush paper(Gdiplus::Color(255, 218, 187, 128));
  Gdiplus::Pen outline(Gdiplus::Color(255, 48, 34, 25), 5.0f);
  outline.SetLineJoin(Gdiplus::LineJoinMiter);
  graphics.FillPath(&paper, &panel);
  graphics.SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);
  const Gdiplus::GraphicsState saved = graphics.Save();
  graphics.SetClip(&panel);

  const int width = bounds.right - bounds.left;
  const int height = bounds.bottom - bounds.top;
  Gdiplus::LinearGradientBrush age_wash(
      Gdiplus::Point(bounds.left, bounds.top),
      Gdiplus::Point(bounds.right, bounds.bottom),
      Gdiplus::Color(18, 255, 238, 185), Gdiplus::Color(55, 83, 47, 29));
  graphics.FillPath(&age_wash, &panel);

  Gdiplus::SolidBrush stain(Gdiplus::Color(45, 105, 57, 31));
  Gdiplus::SolidBrush pale_stain(Gdiplus::Color(30, 255, 227, 164));
  for (int index = 0; index < 11; ++index) {
    const int blot_width = 19 + ((seed * 31 + index * 23) % 58);
    const int blot_height = std::max(9, blot_width / 2);
    const int x = bounds.left + 18 +
                  ((seed * 73 + index * 167) % std::max(1, width - 72));
    const int y = bounds.top + 18 +
                  ((seed * 109 + index * 131) % std::max(1, height - 62));
    graphics.FillEllipse(index % 4 == 0 ? &pale_stain : &stain, x, y,
                         blot_width, blot_height);
  }

  Gdiplus::Pen crease_shadow(Gdiplus::Color(64, 75, 48, 30), 3.0f);
  Gdiplus::Pen crease_highlight(Gdiplus::Color(49, 255, 239, 185), 2.0f);
  auto draw_crease = [&](std::vector<Gdiplus::Point> crease) {
    graphics.DrawLines(&crease_shadow, crease.data(),
                       static_cast<INT>(crease.size()));
    for (Gdiplus::Point& point : crease) {
      point.X += 2;
      point.Y -= 2;
    }
    graphics.DrawLines(&crease_highlight, crease.data(),
                       static_cast<INT>(crease.size()));
  };
  const int horizontal_creases = height > 500 ? 5 : 3;
  for (int crease = 0; crease < horizontal_creases; ++crease) {
    std::vector<Gdiplus::Point> points;
    const int base_y = bounds.top + (crease + 1) * height /
                                        (horizontal_creases + 1);
    for (int point = 0; point < 6; ++point) {
      const int x = bounds.left + point * width / 5;
      const int bend = ((seed * 17 + crease * 29 + point * 37) % 39) - 19;
      points.push_back({x, base_y + bend});
    }
    draw_crease(std::move(points));
  }
  const int vertical_creases = width > 700 ? 3 : 1;
  for (int crease = 0; crease < vertical_creases; ++crease) {
    std::vector<Gdiplus::Point> points;
    const int base_x = bounds.left + (crease + 1) * width /
                                        (vertical_creases + 1);
    for (int point = 0; point < 6; ++point) {
      const int y = bounds.top + point * height / 5;
      const int bend = ((seed * 23 + crease * 31 + point * 41) % 35) - 17;
      points.push_back({base_x + bend, y});
    }
    draw_crease(std::move(points));
  }

  Gdiplus::SolidBrush edge_grime(Gdiplus::Color(78, 55, 35, 24));
  const int grime = 10;
  graphics.FillRectangle(&edge_grime, bounds.left, bounds.top, width, grime);
  graphics.FillRectangle(&edge_grime, bounds.left, bounds.bottom - grime,
                         width, grime);
  graphics.FillRectangle(&edge_grime, bounds.left, bounds.top, grime, height);
  graphics.FillRectangle(&edge_grime, bounds.right - grime, bounds.top, grime,
                         height);

  Gdiplus::Point folded_corner[] = {
      {bounds.right - 82, bounds.bottom - 3},
      {bounds.right - 3, bounds.bottom - 72},
      {bounds.right - 3, bounds.bottom - 3},
  };
  Gdiplus::SolidBrush fold_shadow(Gdiplus::Color(66, 76, 48, 31));
  Gdiplus::Pen fold_edge(Gdiplus::Color(78, 82, 57, 39), 2.0f);
  graphics.FillPolygon(&fold_shadow, folded_corner,
                       static_cast<INT>(std::size(folded_corner)));
  graphics.DrawLines(&fold_edge, folded_corner,
                     static_cast<INT>(std::size(folded_corner)));
  graphics.Restore(saved);

  graphics.SetSmoothingMode(Gdiplus::SmoothingModeNone);
  graphics.DrawPath(&outline, &panel);
  Gdiplus::SolidBrush edge_chip(Gdiplus::Color(255, 30, 20, 17));
  const int chip_count = std::max(7, width / 110);
  for (int index = 0; index < chip_count; ++index) {
    const int size = 6 + ((seed * 5 + index * 7) % 9);
    const int x = bounds.left + 24 +
                  ((seed * 47 + index * 139) % std::max(1, width - 52));
    const int y = bounds.top + 20 +
                  ((seed * 61 + index * 113) % std::max(1, height - 46));
    if (index % 2 == 0) {
      graphics.FillRectangle(&edge_chip, x, bounds.top - 2, size, size + 5);
      graphics.FillRectangle(&edge_chip, bounds.right - size - 1, y, size + 3,
                             size);
    } else {
      graphics.FillRectangle(&edge_chip, x, bounds.bottom - size - 3, size,
                             size + 5);
      graphics.FillRectangle(&edge_chip, bounds.left - 2, y, size + 5, size);
    }
  }

  graphics.SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);
  Gdiplus::Pen scratch(Gdiplus::Color(78, 78, 57, 40), 2.0f);
  for (int index = 0; index < 7; ++index) {
    const int x = bounds.left + 14 + index * 7;
    const int y = bounds.bottom - 12 + (index % 3) * 3;
    graphics.DrawLine(&scratch, x, y, x + 17, y - 19);
  }
}

void DrawCustomBackdrop(HDC dc) {
  Fill(dc, Rect(0, 0, kWidth, kHeight), RGB(30, 20, 17));
  Gdiplus::Graphics graphics(dc);
  graphics.SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);
  Gdiplus::SolidBrush mottle(Gdiplus::Color(22, 150, 93, 62));
  for (int index = 0; index < 34; ++index) {
    const int x = (index * 211 + 47) % kWidth;
    const int y = (index * 137 + 31) % kHeight;
    const int width = 35 + (index * 19) % 95;
    graphics.FillEllipse(&mottle, x, y, width, width / 3 + 10);
  }
  Gdiplus::Pen scratch(Gdiplus::Color(45, 187, 145, 102), 2.0f);
  for (int index = 0; index < 18; ++index) {
    const int x = (index * 263 + 29) % kWidth;
    const int y = (index * 181 + 53) % kHeight;
    graphics.DrawLine(&scratch, x, y, x + 24, y - 17);
  }
}

void DrawStaticPage(Page page, HDC dc) {
  // All dashboards share one continuous parchment. Stats and run info add
  // their page-specific paper layers at render time instead of swapping the
  // entire authored background.
  if (!DrawAssetStretch(dc, "custom/background.png",
                         Rect(0, 0, kWidth, kHeight), true)) {
    // Keep a usable panel if an installation is interrupted before the new
    // authored background is copied into place.
    DrawCustomBackdrop(dc);
    DrawAgedPanel(dc, Rect(22, 22, 306, 350), 4);
    DrawAgedPanel(dc, Rect(324, 22, 1218, 350), 8);
    DrawAgedPanel(dc, Rect(22, 370, 1218, 1058), 1);
  }

  DrawAssetRegion(dc, "custom/background.png", Rect(340, 286, 1120, 320),
                  Rect(320, 110, 1200, 278), false);

  // These centers are derived from the printed boxes in the two authored
  // sheets at the native 1240x1080 panel size.
  g_font.Draw(dc, "RUN TIME", 166.0f, 84.0f, 24.0f, kInk, true);
  const char* titles[] = {"ITEMS", "DUNGEON MAP", "STATS", "RUN INFO", "ITEM PICKUPS", "COMPLETION / PROGRESS"};
  const char* title = titles[static_cast<size_t>(page)];
  const bool map_page = page == Page::kMap;
  const bool centered_page = page != Page::kStats && page != Page::kRunInfo;
  g_font.Draw(dc, title, centered_page ? 620.0f : 790.0f,
              map_page ? 520.0f : 500.0f,
              page == Page::kProgress ? 38.0f : 48.0f, kInk, true);

  AlphaFill(dc, Rect(60, 168, 272, 170), 105, kInk);
  if (map_page) {
    constexpr int title_rule_y = 597;
    AlphaFill(dc, Rect(444, title_rule_y, 532, title_rule_y + 2), 135, kInk);
    AlphaFill(dc, Rect(708, title_rule_y, 796, title_rule_y + 2), 135, kInk);
    Frame(dc, Rect(537, title_rule_y - 5, 547, title_rule_y + 5), kInk, 2);
    Frame(dc, Rect(693, title_rule_y - 5, 703, title_rule_y + 5), kInk, 2);
  }
}

const char* RoomIcon(int type) {
  switch (type) {
    case 2:
      return "IconShop";
    case 4:
      return "IconTreasureRoom";
    case 5:
      return "IconBoss";
    case 6:
      return "IconMiniboss";
    case 7:
      return "IconSecretRoom";
    case 8:
      return "IconSuperSecretRoom";
    case 9:
      return "IconArcade";
    case 10:
      return "IconCurseRoom";
    case 11:
      return "IconAmbushRoom";
    case 12:
      return "IconLibrary";
    case 13:
      return "IconSacrificeRoom";
    case 14:
      return "IconDevilRoom";
    case 15:
      return "IconAngelRoom";
    case 18:
      return "IconIsaacsRoom";
    case 19:
      return "IconBarrenRoom";
    case 20:
      return "IconChestRoom";
    case 21:
      return "IconDiceRoom";
    case 24:
      return "IconPlanetarium";
    case 25:
    case 26:
      return "IconTeleporterRoom";
    case 29:
      return "IconUltraSecretRoom";
    default:
      return nullptr;
  }
}

bool DrawNativeDungeon(HDC dc,const RECT& area) {
  const auto& native=g_state.native_map;
  if(!native.received) return false;
  if(native.commands.empty()) return true;
  const char* actors[]={"minimap1","minimap2","minimap_icons"};
  double left=1e9,top=1e9,right=-1e9,bottom=-1e9;
  for(const auto& c:native.commands) {
    const auto* sprite=FindSprite(actors[c.actor],c.animation.c_str(),c.frame);
    if(!sprite) return false;
    left=std::min(left,c.x-sprite->pivot_x*c.scale_x);
    top=std::min(top,c.y-sprite->pivot_y*c.scale_y);
    right=std::max(right,c.x+(sprite->width-sprite->pivot_x)*c.scale_x);
    bottom=std::max(bottom,c.y+(sprite->height-sprite->pivot_y)*c.scale_y);
  }
  if(right<=left || bottom<=top) return false;
  const double scale=std::min(5.0,std::min((area.right-area.left)/(right-left),(area.bottom-area.top)/(bottom-top)));
  const double ox=(area.left+area.right-(right-left)*scale)/2,oy=(area.top+area.bottom-(bottom-top)*scale)/2;
  const int saved=SaveDC(dc);IntersectClipRect(dc,area.left,area.top,area.right,area.bottom);
  for(const auto& c:native.commands) {
    const auto* sprite=FindSprite(actors[c.actor],c.animation.c_str(),c.frame);
    const int x=static_cast<int>(std::round(ox+(c.x-sprite->pivot_x*c.scale_x-left)*scale));
    const int y=static_cast<int>(std::round(oy+(c.y-sprite->pivot_y*c.scale_y-top)*scale));
    DrawSpriteColor(dc,sprite,Rect(x,y,x+static_cast<int>(std::round(sprite->width*c.scale_x*scale)),y+static_cast<int>(std::round(sprite->height*c.scale_y*scale))),
        static_cast<float>(c.red),static_cast<float>(c.green),static_cast<float>(c.blue),static_cast<float>(c.alpha));
  }
  RestoreDC(dc,saved);return true;
}

void DrawDungeon(HDC dc) {
  const MapState& map = g_state.map;
  const RECT area = Rect(76, 646, 1164, 1008);
  if (map.curses & 4) {
    g_font.Draw(dc, "?", 620.0f, 770.0f, 64.0f, kInk, true);
    return;
  }
  if(DrawNativeDungeon(dc,area)) return;
  int min_x = 13, min_y = 13, max_x = 0, max_y = 0;
  bool any = false;
  for (const Room& room : map.rooms) {
    if (!MapRoomVisible(room)) continue;
    const auto size = MapRoomSize(room.shape);
    min_x = std::min(min_x, MapRoomX(room));
    min_y = std::min(min_y, room.grid / 13);
    max_x = std::max(max_x, MapRoomX(room) + size.first);
    max_y = std::max(max_y, room.grid / 13 + size.second);
    any = true;
  }
  if (!any) return;
  // Keep the game's 18x16 grid and the ANM2's full shape sprite. L-shaped
  // rooms must not be reconstructed as independent square room tiles.
  const double scale = std::min(5.0, std::min(
      double(area.right - area.left) / ((max_x - min_x) * 18),
      double(area.bottom - area.top) / ((max_y - min_y) * 16)));
  const double ox = (area.left + area.right - (max_x - min_x) * 18 * scale) / 2.0;
  const double oy = (area.top + area.bottom - (max_y - min_y) * 16 * scale) / 2.0;
  const int saved = SaveDC(dc);
  IntersectClipRect(dc, area.left, area.top, area.right, area.bottom);
  for (const Room& room : map.rooms) {
    if (!MapRoomVisible(room)) continue;
    const bool current = room.grid == map.current_grid || room.safe_grid == map.current_grid;
    const char* animation = current ? "RoomCurrent" :
                             (MapRoomVisited(room) ? "RoomVisited" : "RoomUnvisited");
    const auto size = MapRoomSize(room.shape);
    const int x = static_cast<int>(ox + (MapRoomX(room) - min_x) * 18 * scale);
    const int y = static_cast<int>(oy + (room.grid / 13 - min_y) * 16 * scale);
    const RECT bounds = Rect(x, y, x + static_cast<int>(size.first * 18 * scale),
                            y + static_cast<int>(size.second * 16 * scale));
    if (MapRoomBody(room, current))
      DrawSprite(dc, FindSprite("minimap2", animation, room.shape - 1), bounds, room.flags>=0 && (room.flags & 1024));
    // A known outline is not permission to disclose the room's type.
    const char* icon = (room.display_flags & 4) ? RoomIcon(room.type) : nullptr;
    if (icon && room.type == 4 && room.flags>=0 && (room.flags & 2048)) icon = "IconTreasureRoomRed";
    if (icon && room.type == 11 && room.subtype == 1) icon = "IconBossAmbushRoom";
    if (MapRoomLocked(room)) icon = "IconLockedRoom";
    if (icon) {
      const int center_x = x + static_cast<int>(9 * scale);
      const int center_y = y + static_cast<int>(8 * scale);
      const int half = static_cast<int>(5 * scale);
      DrawSprite(dc, FindSprite("minimap_icons", icon),
                 Rect(center_x-half, center_y-half, center_x+half, center_y+half));
    }
  }
  RestoreDC(dc, saved);
}

[[maybe_unused]] void DrawMapPage(HDC dc) {
  const std::string floor = Clean(g_state.map.floor, "UNKNOWN FLOOR");
  g_font.Draw(dc, Upper(floor), 422.0f, 90.0f, 29.0f, kInk, true);
  DrawDungeon(dc);

  const PlayerMetadata* player = FindPlayer(g_state.character_id);
  if (player) DrawAssetFit(dc, player->asset, Rect(900, 35, 1175, 240), false);
  const std::string fallback = player ? player->name : "UNKNOWN";
  g_font.Draw(dc, Upper(Clean(g_state.character_name, fallback)), 1037.0f,
              255.0f, 30.0f, kInk, true);

  // hudpickups frames are coin, key, bomb in that order.
  const int values[] = {g_state.consumables.coins, g_state.consumables.keys,
                        g_state.consumables.bombs};
  for (int index = 0; index < 3; ++index) {
    const int y = 392 + index * 135;
    DrawSprite(dc, FindSprite("hudpickups", "Idle", index),
               Rect(895, y, 979, y + 84));
    g_font.Draw(dc, Number(values[index], 2), 1020.0f,
                static_cast<float>(y + 35), 48.0f, kInk);
  }
}

void DrawHeart(HDC dc, const char* animation, int x, int y, int size = 48) {
  DrawSprite(dc, FindSprite("ui_hearts", animation),
             Rect(x, y, x + size, y + size));
}

[[maybe_unused]] void DrawHearts(HDC dc, int start_x, int start_y) {
  const Health& health = g_state.health;
  const int red_containers = (health.max_hearts + 1) / 2;
  int remaining_red = std::max(0, health.hearts - health.rotten_hearts * 2);
  for (int index = 0; index < std::min(red_containers, 18); ++index) {
    const int x = start_x + (index % 12) * 48;
    const int y = start_y + (index / 12) * 48;
    const char* animation = nullptr;
    if (index >= red_containers - health.rotten_hearts)
      animation = "RottenHeartFull";
    else if (remaining_red >= 2)
      animation = "RedHeartFull";
    else if (remaining_red == 1)
      animation = "RedHeartHalf";
    else
      animation = "EmptyHeart";
    DrawHeart(dc, animation, x, y);
    if (animation[0] != 'R' || animation[1] != 'o')
      remaining_red = std::max(0, remaining_red - 2);
  }
  const int soul_count = (health.soul_hearts + 1) / 2;
  int remaining_soul = health.soul_hearts;
  for (int index = 0; index < std::min(soul_count, 18); ++index) {
    const int visual_index = red_containers + index;
    const int x = start_x + (visual_index % 12) * 48;
    const int y = start_y + (visual_index / 12) * 48;
    const bool black = (health.black_heart_mask & (1ull << index)) != 0;
    const char* animation = black
                                ? (remaining_soul == 1 ? "BlackHeartHalf"
                                                       : "BlackHeartFull")
                                : (remaining_soul == 1 ? "BlueHeartHalf"
                                                       : "BlueHeartFull");
    DrawHeart(dc, animation, x, y);
    remaining_soul = std::max(0, remaining_soul - 2);
  }
  int visual_index = red_containers + soul_count;
  const std::pair<const char*, int> specials[] = {
      {"BoneHeartFull", health.bone_hearts},
      {"WhiteHeartHalf", health.eternal_hearts},
      {"GoldHeartOverlay", health.golden_hearts},
      {"BrokenHeart", health.broken_hearts},
  };
  for (const auto& special : specials) {
    for (int count = 0; count < std::min(special.second, 12); ++count) {
      DrawHeart(dc, special.first, start_x + (visual_index % 12) * 48,
                start_y + (visual_index / 12) * 48);
      ++visual_index;
    }
  }
}

void DrawStatsPage(HDC dc);
void DrawRunInfoPage(HDC dc);
void DrawItemsPage(HDC dc);
void DrawProgressPage(HDC dc);
std::string CurseNames(int mask);

void DrawItemAsset(HDC dc, const ItemMetadata* metadata, const RECT& bounds) {
  if (metadata) DrawAssetFit(dc, metadata->asset, bounds, false);
}

const SpriteFrame* PocketFrame(const PocketItem& item) {
  if (item.kind == PocketKind::kCard && !item.hud_animation.empty()) {
    if (const SpriteFrame* frame =
            FindSprite("ui_cardfronts", item.hud_animation.c_str()))
      return frame;
  }
  const PocketMetadata* metadata = FindPocket(item.id);
  const int frame_index = metadata ? metadata->pickup_frame : item.color;
  if (const SpriteFrame* frame =
          FindSprite("ui_cardspills", "CardFronts", frame_index))
    return frame;
  return FindSprite("ui_cardspills", "CardFronts",
                    std::max(0, frame_index - 1));
}

std::string RunTimer() {
  const uint64_t seconds = g_state.run.timer_frames / 30;
  char timer[32] = {};
  std::snprintf(timer, sizeof(timer), "%02llu:%02llu:%02llu",
                static_cast<unsigned long long>(seconds / 3600),
                static_cast<unsigned long long>((seconds / 60) % 60),
                static_cast<unsigned long long>(seconds % 60));
  return timer;
}

[[maybe_unused]] void DrawSlotName(HDC dc, const std::string& value, const RECT& slot,
                  float y, int capacity) {
  const std::string name = Upper(Clean(value, "EMPTY"));
  const float center = (slot.left + slot.right) * 0.5f;
  const float width = static_cast<float>(slot.right - slot.left - 8);
  const auto lines = g_font.Wrap(name, capacity > 1 ? 15.0f : 18.0f, width);
  if (!lines.empty())
    g_font.Draw(dc, lines.front(), center, y, capacity > 1 ? 15.0f : 18.0f,
                kInk, true);
}

void DrawChargeBar(HDC dc, const ActiveItem& item,
                   const ItemMetadata* metadata, const RECT& slot) {
  const int maximum = metadata ? metadata->max_charges : 0;
  const float center = (slot.left + slot.right) * 0.5f;
  if (maximum <= 0) {
    return;
  }

  const int segments = std::clamp(maximum, 1, 20);
  const int charge = std::clamp(item.charge, 0, maximum);
  const int battery = std::clamp(item.battery_charge, 0, maximum);
  std::string value = Number(charge) + "/" + Number(maximum);
  if (battery > 0) value += " +" + Number(battery);
  if(slot.right-slot.left>100) g_font.Draw(dc, value, center, static_cast<float>(slot.bottom - 52), 15.0f,
              kInk, true);

  const RECT bar = Rect(slot.left + 10, slot.bottom - 26,
                        slot.right - 10, slot.bottom - 9);
  const int width = bar.right - bar.left;
  const int gap = segments >= 12 ? 1 : 2;
  for (int index = 0; index < segments; ++index) {
    RECT section = Rect(bar.left + width * index / segments,
                        bar.top,
                        bar.left + width * (index + 1) / segments -
                            (index + 1 < segments ? gap : 0),
                        bar.bottom);
    Fill(dc, section, index < charge ? RGB(73, 143, 58)
                                     : RGB(71, 64, 50));
    if (index < battery) {
      const RECT overcharge = Rect(section.left + 1, section.top + 1,
                                   section.right - 1, section.top + 5);
      Fill(dc, overcharge, RGB(173, 208, 83));
    }
    Frame(dc, section, RGB(49, 42, 32), 1);
  }
}

void DrawActiveSlots(HDC dc) {
  std::array<const ActiveItem*, 2> entries = {};
  for (const ActiveItem& item : g_state.inventory.actives) {
    if (item.slot >= 0 && item.slot < 2 && !entries[item.slot])
      entries[item.slot] = &item;
  }
  const auto slots = HudSlots(328, 612, entries[1] != nullptr);
  for (int index = 0; index < 2; ++index) {
    const ActiveItem* item = entries[index];
    if (index == 0 || item)
      DrawAssetStretch(dc, "custom/item_slot_dark.png", slots[index], false);
    if (!item) continue;
    const ItemMetadata* metadata = FindCollectible(item->id);
    const bool charged = metadata && metadata->max_charges > 0;
    DrawItemAsset(dc, metadata,
                  Rect(slots[index].left + 12, slots[index].top + 6,
                       slots[index].right - 12, slots[index].bottom - (charged ? (index ? 32 : 55) : 12)));
    DrawChargeBar(dc, *item, metadata, slots[index]);
  }
}

void DrawPocketSlots(HDC dc) {
  std::array<const PocketItem*, 2> entries = {};
  for (const PocketItem& item : g_state.inventory.pockets) {
    if (item.slot >= 0 && item.slot < 2 && !entries[item.slot])
      entries[item.slot] = &item;
  }
  const auto slots = HudSlots(638, 911, entries[1] != nullptr);
  for (int index = 0; index < 2; ++index) {
    const PocketItem* item = entries[index];
    if (index == 0 || item)
      DrawAssetStretch(dc, "custom/item_slot_dark.png", slots[index], false);
    if (!item) continue;
    const int slot_width = slots[index].right - slots[index].left;
    const int image_height_limit = index == 0 ? 122 : 79;
    const int image_width = item->kind == PocketKind::kPill
                                ? std::min(slot_width - 26, index == 0 ? 76 : 49)
                                : std::min(slot_width - 20,
                                           image_height_limit * 2 / 3);
    const int image_height = item->kind == PocketKind::kPill
                                 ? image_width
                                 : image_height_limit;
    const int image_left =
        (slots[index].left + slots[index].right - image_width) / 2;
    const int image_top = (slots[index].top + slots[index].bottom - image_height) / 2;
    const RECT destination = Rect(image_left, image_top,
                                  image_left + image_width,
                                  image_top + image_height);
    if (item->kind == PocketKind::kPill) {
      const int color = std::max(1, item->color & 0x7FF) - 1;
      const RECT source = Rect((color % 8) * 32, (color / 8) * 32,
                               (color % 8 + 1) * 32,
                               (color / 8 + 1) * 32);
      DrawAssetRegion(dc, "game/gfx/items/pick ups/pickup_007_pill.png",
                      source, destination, false);
    } else {
      DrawSprite(dc, PocketFrame(*item), destination);
    }
  }
}

void DrawTrinketSlots(HDC dc) {
  std::array<const Trinket*, 2> entries = {};
  for (const Trinket& item : g_state.inventory.trinkets) {
    if (item.slot >= 0 && item.slot < 2 && !entries[item.slot])
      entries[item.slot] = &item;
  }
  const auto slots = HudSlots(922, 1197, entries[1] != nullptr);
  for (int index = 0; index < 2; ++index) {
    const Trinket* item = entries[index];
    if (index == 0 || item)
      DrawAssetStretch(dc, "custom/item_slot_dark.png", slots[index], false);
    if (!item) continue;
    DrawItemAsset(dc, FindTrinket(item->id),
                  Rect(slots[index].left + 14, slots[index].top + 14,
                       slots[index].right - 14, slots[index].bottom - 14));
    if (item->golden)
      Frame(dc, Rect(slots[index].left + 8, slots[index].top + 8,
                     slots[index].right - 8, slots[index].bottom - 8),
            RGB(177, 132, 35), 3);
  }
}

void DrawRewardChances(HDC dc) {
  const char* icons[] = {"IconDevilRoom", "IconAngelRoom", "IconPlanetarium"};
  const double values[] = {g_state.run.devil_chance,
                           g_state.run.angel_chance,
                           g_state.run.planetarium_chance};
  const int icon_centers[] = {180, 535, 885};
  const int value_centers[] = {310, 660, 1025};
  const COLORREF colors[] = {kWine, kInk, RGB(67, 63, 107)};
  for (int index = 0; index < 3; ++index) {
    DrawSprite(dc, FindSprite("minimap_icons", icons[index]),
               Rect(icon_centers[index] - 21, 423,
                    icon_centers[index] + 21, 459));
    g_font.Draw(dc, Percent(values[index]),
                static_cast<float>(value_centers[index]), 425.0f, 28.0f,
                colors[index], true);
  }
}

std::string HalfHeartValue(int half_hearts) {
  const int clamped = std::max(0, half_hearts);
  if ((clamped & 1) == 0) return Number(clamped / 2);
  char result[32] = {};
  std::snprintf(result, sizeof(result), "%.1f", clamped / 2.0);
  return result;
}

int CountBits(uint64_t value) {
  int count = 0;
  while (value) {
    count += static_cast<int>(value & 1u);
    value >>= 1u;
  }
  return count;
}

[[maybe_unused]] bool IsRune(const PocketItem& item) {
  if (item.kind != PocketKind::kCard) return false;
  const std::string name = Upper(item.name);
  return name.find("RUNE") != std::string::npos ||
         name.find("SOUL OF") != std::string::npos;
}

void DrawCharacterStickyNote(HDC dc, const PlayerMetadata* player,
                             const std::string& name) {
  DrawAssetFit(dc, "custom/sticky_note.png", Rect(38, 570, 432, 964), false);
  if (player) DrawAssetFit(dc, player->asset, Rect(116, 642, 354, 832), false);
  g_font.Draw(dc, Upper(name), 235.0f, 848.0f, 32.0f, kInk, true);
}

void DrawStatsMetricIcon(HDC dc, int index, const RECT& bounds) {
  switch (index) {
    case 0:
      DrawSprite(dc, FindSprite("ui_hearts", "RedHeartFull"), bounds);
      return;
    case 1:
      DrawSprite(dc, FindSprite("ui_hearts", "BlueHeartFull"), bounds);
      return;
    case 2:
      DrawSprite(dc, FindSprite("ui_hearts", "BlackHeartFull"), bounds);
      return;
    case 3:
      DrawItemAsset(dc, FindCollectible(7), bounds);  // Blood of the Martyr.
      return;
    case 4:
      DrawItemAsset(dc, FindCollectible(1), bounds);  // The Sad Onion.
      return;
    case 5:
      DrawItemAsset(dc, FindCollectible(5), bounds);  // My Reflection.
      return;
    case 6:
      DrawItemAsset(dc, FindCollectible(6), bounds);  // Number One.
      return;
    case 7:
      DrawItemAsset(dc, FindCollectible(27), bounds);  // Wooden Spoon.
      return;
    case 8:
      DrawItemAsset(dc, FindTrinket(42), bounds);  // Lucky Toe.
      return;
    case 9:
      DrawSprite(dc, FindSprite("minimap_icons", "IconDevilRoom"), bounds);
      return;
    case 10:
      DrawSprite(dc, FindSprite("minimap_icons", "IconAngelRoom"), bounds);
      return;
    default:
      return;
  }
}

void DrawStatsPage(HDC dc) {
  const PlayerMetadata* player = FindPlayer(g_state.character_id);
  const std::string name = Clean(g_state.character_name,
                                 player ? player->name : "UNKNOWN");
  DrawCharacterStickyNote(dc, player, name);

  const int black_half_hearts = CountBits(g_state.health.black_heart_mask);
  const std::array<std::pair<std::string, std::string>, 11> metrics = {{
      {"HEALTH", HalfHeartValue(g_state.health.hearts)},
      {"SOUL HEARTS", HalfHeartValue(g_state.health.soul_hearts)},
      {"BLACK HEARTS", HalfHeartValue(black_half_hearts)},
      {"DAMAGE", Decimal(g_state.stats.damage)},
      {"TEARS", Decimal(g_state.stats.tears)},
      {"RANGE", Decimal(g_state.stats.range)},
      {"SHOT SPEED", Decimal(g_state.stats.shot_speed)},
      {"SPEED", Decimal(g_state.stats.move_speed)},
      {"LUCK", Decimal(g_state.stats.luck)},
      {"DEVIL DEAL CHANCE", Percent(g_state.run.devil_chance)},
      {"ANGEL DEAL CHANCE", Percent(g_state.run.angel_chance)},
  }};
  for (size_t index = 0; index < metrics.size(); ++index) {
    const float y = 590.0f + static_cast<float>(index) * 34.0f;
    DrawStatsMetricIcon(dc, static_cast<int>(index),
                        Rect(422, static_cast<int>(y) - 1, 456,
                             static_cast<int>(y) + 33));
    g_font.Draw(dc, metrics[index].first, 468.0f, y, 21.0f, kInk);
    float value_height = 22.0f;
    while (value_height > 15.0f &&
           g_font.Measure(metrics[index].second, value_height) > 102.0f)
      value_height -= 1.0f;
    g_font.Draw(dc, metrics[index].second, 760.0f, y, value_height, kInk,
                true);
  }


}

// Six dots and the neighbouring page names make the swipe navigation visible.
void DrawPageIndicator(HDC dc) {
  const char* names[] = {"ITEMS", "MAP", "STATS", "RUN", "PICKUPS", "PROGRESS"};
  const int current = static_cast<int>(g_page);
  const int spacing = 26;
  const int left = 620 - (kDashboardPageCount - 1) * spacing / 2;
  for (int index = 0; index < kDashboardPageCount; ++index) {
    const int cx = left + index * spacing;
    const int radius = index == current ? 7 : 4;
    Fill(dc, Rect(cx - radius, 1024 - radius, cx + radius, 1024 + radius),
         index == current ? kWine : kInkMuted);
  }
  const int previous = (current + kDashboardPageCount - 1) % kDashboardPageCount;
  const int next = (current + 1) % kDashboardPageCount;
  g_font.Draw(dc, std::string("< ") + names[previous], 300.0f, 1015.0f, 16.0f, kInkMuted, true);
  g_font.Draw(dc, std::string(names[next]) + " >", 940.0f, 1015.0f, 16.0f, kInkMuted, true);
}

void DrawDashboard(HDC dc) {
  DrawRewardChances(dc);

  g_font.Draw(dc, RunTimer(), 166.0f, 116.0f, 38.0f, kInk, true);
  // Match the game's hudpickups frame order: coin, key, bomb.
  const int values[] = {g_state.consumables.coins, g_state.consumables.keys,
                        g_state.consumables.bombs};
  const int centers[] = {84, 166, 248};
  for (int index = 0; index < 3; ++index) {
    DrawSprite(dc, FindSprite("hudpickups", "Idle", index),
               Rect(centers[index] - 25, 190,
                    centers[index] + 25, 240));
    g_font.Draw(dc, Number(values[index], 2), static_cast<float>(centers[index]),
                250.0f, 30.0f, kInk, true);
  }

  DrawActiveSlots(dc);
  DrawPocketSlots(dc);
  DrawTrinketSlots(dc);

  DrawPageIndicator(dc);
  if (g_page == Page::kStats) {
    DrawStatsPage(dc);
  } else if (g_page == Page::kRunInfo) {
    DrawRunInfoPage(dc);
  } else if (ItemPage()) {
    DrawItemsPage(dc);
  } else if (g_page == Page::kProgress) {
    DrawProgressPage(dc);
  } else {
    const std::string floor = Upper(Clean(
        g_state.map.floor, Clean(g_state.run.floor, "UNKNOWN FLOOR")));
    g_font.Draw(dc, floor, 620.0f, 578.0f, 28.0f, kInk, true);
    if (g_state.map.curses != 0)
      g_font.Draw(dc, CurseNames(g_state.map.curses), 620.0f, 611.0f,
                  18.0f, kWine, true);
    DrawDungeon(dc);
  }
}

void DrawItemPageArrow(HDC dc, bool points_left) {
  const int center_x = points_left ? 170 : 1070;
  const int center_y = kItemGridTop + kItemGridHeight / 2;
  POINT outer[3] = {};
  POINT inner[3] = {};
  if (points_left) {
    outer[0] = {center_x + 17, center_y - 34};
    outer[1] = {center_x - 19, center_y};
    outer[2] = {center_x + 17, center_y + 34};
    inner[0] = {center_x + 10, center_y - 21};
    inner[1] = {center_x - 10, center_y};
    inner[2] = {center_x + 10, center_y + 21};
  } else {
    outer[0] = {center_x - 17, center_y - 34};
    outer[1] = {center_x + 19, center_y};
    outer[2] = {center_x - 17, center_y + 34};
    inner[0] = {center_x - 10, center_y - 21};
    inner[1] = {center_x + 10, center_y};
    inner[2] = {center_x - 10, center_y + 21};
  }
  HBRUSH outer_brush = CreateSolidBrush(kInk);
  HBRUSH inner_brush = CreateSolidBrush(RGB(161, 124, 80));
  HPEN pen = CreatePen(PS_NULL, 0, kInk);
  HGDIOBJ old_brush = SelectObject(dc, outer_brush);
  HGDIOBJ old_pen = SelectObject(dc, pen);
  Polygon(dc, outer, 3);
  SelectObject(dc, inner_brush);
  Polygon(dc, inner, 3);
  SelectObject(dc, old_brush);
  SelectObject(dc, old_pen);
  DeleteObject(outer_brush);
  DeleteObject(inner_brush);
  DeleteObject(pen);
}

void DrawItemSelection(HDC dc, const RECT& slot) {
  constexpr int length = 17;
  constexpr int weight = 4;
  constexpr int inset = 5;
  const COLORREF marker = RGB(241, 236, 214);
  const int left = slot.left - inset;
  const int top = slot.top - inset;
  const int right = slot.right + inset;
  const int bottom = slot.bottom + inset;
  Fill(dc, Rect(left, top, left + length, top + weight), marker);
  Fill(dc, Rect(left, top, left + weight, top + length), marker);
  Fill(dc, Rect(right - length, top, right, top + weight), marker);
  Fill(dc, Rect(right - weight, top, right, top + length), marker);
  Fill(dc, Rect(left, bottom - weight, left + length, bottom), marker);
  Fill(dc, Rect(left, bottom - length, left + weight, bottom), marker);
  Fill(dc, Rect(right - length, bottom - weight, right, bottom), marker);
  Fill(dc, Rect(right - weight, bottom - length, right, bottom), marker);
}

struct ShownDescription {
  std::string name;
  std::string text;
  const ItemMetadata* icon = nullptr;
  int quality = -1;
  bool held = false;
  bool from_eid = false;
};

// Resolves what the description box shows: a tapped HUD slot first, then the
// selected grid item. Live text (EID when installed) wins over the catalog.
bool ResolveDescription(ShownDescription* out) {
  std::string key = g_focus_key;
  if (key.empty() && g_selected_item > 0) key = "c" + std::to_string(g_selected_item);
  if (key.empty()) return false;
  const char kind = key.front();
  const int id = std::atoi(key.c_str() + 1);
  if (kind == 'c') {
    out->icon = FindCollectible(id);
    if (out->icon) {
      out->name = out->icon->name;
      out->text = out->icon->description;
      out->quality = out->icon->quality;
    }
    for (const CollectedItem& entry : g_state.inventory.collectibles)
      if (entry.id == id) out->held = true;
  } else if (kind == 't') {
    out->icon = FindTrinket(id);
    if (out->icon) { out->name = out->icon->name; out->text = out->icon->description; }
    out->held = true;
  } else {
    for (const PocketItem& pocket : g_state.inventory.pockets) {
      const bool match = (kind == 'k' && pocket.kind == PocketKind::kCard && pocket.id == id) ||
                         (kind == 'p' && pocket.kind == PocketKind::kPill && pocket.id == id);
      if (match) { out->name = pocket.name; out->held = true; }
    }
  }
  const auto found = g_state.descriptions.entries.find(key);
  if (found != g_state.descriptions.entries.end()) {
    if (!found->second.name.empty() && found->second.name.front() != '#')
      out->name = found->second.name;
    if (!found->second.text.empty() && found->second.text.front() != '#')
      out->text = found->second.text;
    out->from_eid = found->second.from_eid;
  }
  return !out->name.empty() || !out->text.empty();
}

void DrawDescriptionBox(HDC dc) {
  const RECT box = Rect(110, 784, 1130, 1006);
  DrawAssetStretch(dc, "custom/item_description_panel.png", box, true);
  ShownDescription shown;
  if (!ResolveDescription(&shown)) {
    g_font.Draw(dc, "TAP AN ITEM TO SEE WHAT IT DOES", 620.0f, 884.0f, 24.0f,
                kInk, true);
    return;
  }
  // The authored strip has a fixed-width icon compartment on the left.
  constexpr int text_left = 322;
  if (shown.icon) DrawItemAsset(dc, shown.icon, Rect(150, 836, 260, 946));
  const float max_width = static_cast<float>(box.right - 40 - text_left);
  g_font.Draw(dc, Upper(Clean(shown.name, "UNKNOWN")), static_cast<float>(text_left),
              800.0f, 27.0f, kWine);
  if (shown.quality >= 0)
    g_font.Draw(dc, "QUALITY " + Number(shown.quality), 205.0f, 806.0f, 15.0f,
                kInkMuted, true);
  // EID separates effects with line breaks; render each as its own bullet.
  std::vector<std::string> lines;
  std::string paragraph;
  std::istringstream paragraphs(shown.text);
  bool multiple = shown.text.find('\n') != std::string::npos;
  while (std::getline(paragraphs, paragraph)) {
    if (paragraph.empty()) continue;
    const auto wrapped = g_font.Wrap((multiple ? "- " : "") + paragraph, 22.0f, max_width);
    lines.insert(lines.end(), wrapped.begin(), wrapped.end());
  }
  float y = 840.0f;
  const float bottom = static_cast<float>(box.bottom - 26);
  for (size_t index = 0; index < lines.size(); ++index) {
    if (y + 2 * 26.0f > bottom && index + 1 < lines.size()) {
      g_font.Draw(dc, lines[index] + " ...", static_cast<float>(text_left), y, 22.0f, kInk);
      break;
    }
    g_font.Draw(dc, lines[index], static_cast<float>(text_left), y, 22.0f, kInk);
    y += 26.0f;
  }
  if (!shown.from_eid && g_state.descriptions.received)
    g_font.Draw(dc, "SHORT GAME TEXT (EID NOT LOADED)",
                static_cast<float>(text_left), static_cast<float>(box.bottom - 24), 13.0f,
                kInkMuted);
}

void DrawItemsPage(HDC dc) {
  g_font.Draw(dc,
              Number(static_cast<int>(DisplayItems().size())) +
                  (g_page == Page::kPickups ? " PICKUPS" : " ITEMS HELD"),
              620.0f, 552.0f, 22.0f, kInk, true);

  const int saved = SaveDC(dc);
  IntersectClipRect(dc, kItemGridLeft - 6, kItemGridTop - 6,
                    kItemGridLeft + kItemGridWidth + 6,
                    kItemGridTop + kItemGridHeight + 6);
  const int first_row =
      static_cast<int>(std::floor(g_item_scroll / kItemRowStep));
  const int last_row = static_cast<int>(
      std::ceil((g_item_scroll + kItemGridHeight) / kItemRowStep));
  for (int row = first_row; row < last_row; ++row) {
    for (int column = 0; column < kItemColumns; ++column) {
      const size_t index = static_cast<size_t>(row * kItemColumns + column);
      const int x = kItemGridLeft + column * kItemColumnStep;
      const int y = static_cast<int>(kItemGridTop + row * kItemRowStep -
                                     g_item_scroll);
      const RECT slot = Rect(x, y, x + kItemSlotSize, y + kItemSlotSize);
      DrawAssetStretch(dc, "custom/item_slot_dark.png", slot, true);
      if (index < DisplayItems().size()) {
        const CollectedItem& entry = DisplayItems()[index];
        DrawItemAsset(dc, FindCollectible(entry.id),
                      Rect(x + 12, y + 12, x + kItemSlotSize - 12,
                           y + kItemSlotSize - 12));
        if (entry.count > 1) {
          g_font.Draw(dc, "x" + Number(entry.count),
                      static_cast<float>(x + kItemSlotSize - 28),
                      static_cast<float>(y + kItemSlotSize - 25), 16.0f,
                      RGB(238, 225, 193));
        }
        if (g_focus_key.empty() && entry.id == g_selected_item)
          DrawItemSelection(dc, slot);
      }
    }
  }
  RestoreDC(dc, saved);

  if (MaximumItemScroll() > 0.0f) {
    DrawItemPageArrow(dc, true);
    DrawItemPageArrow(dc, false);
  }
  DrawDescriptionBox(dc);
}

std::string CurseNames(int mask) {
  if (!mask) return "NONE";
  const std::pair<int, const char*> curses[] = {
      {1, "DARKNESS"}, {2, "LABYRINTH"}, {4, "LOST"},
      {8, "UNKNOWN"},  {16, "CURSED"},    {32, "MAZE"},
      {64, "BLIND"},  {128, "GIANT"},
  };
  std::string result;
  for (const auto& curse : curses) {
    if (!(mask & curse.first)) continue;
    if (!result.empty()) result += " / ";
    result += curse.second;
  }
  return result.empty() ? "CURSE " + Number(mask) : result;
}

[[maybe_unused]] const char* TransformationName(int id) {
  constexpr const char* names[] = {
      "GUPPY",          "LORD OF THE FLIES", "MUSHROOM", "ANGEL",
      "BOB",            "DRUGS",             "MOM",      "OH CRAP",
      "BOOKWORM",       "SPIDER BABY",        "ADULT",    "STOMPY",
      "FLIGHT",         "SERAPHIM",           "CONJOINED",
  };
  return id >= 0 && id < static_cast<int>(std::size(names)) ? names[id]
                                                              : nullptr;
}

void DrawGoal(HDC dc) {
  if(!g_state.progress.received) return;
  // A suggested next route milestone, evaluated from native unlock bits.
  // Isaac has branching routes, not a single stored universal quest target.
  struct Milestone { int unlock, boss; const char* requirement; };
  const Milestone milestones[]={
    {4,6,"Defeat Mom to open the Womb"},
    {34,8,"Defeat Mom's Heart 11 times"},
    {57,39,"Defeat Isaac 5 times for the Polaroid"},
    {78,24,"Defeat Satan 5 times for the Negative"},
    {407,63,"Defeat Hush 3 times to open the alternate path"},
    {635,88,"Defeat Mother to open the strange door"}
  };
  const Milestone* target=nullptr;
  for(const auto& milestone:milestones) {
    if(g_state.progress.achievements[milestone.unlock]!='1') { target=&milestone; break; }
  }
  g_font.Draw(dc,"GOAL",790,797,35,kInk,true);
  if(!target) {
    g_font.Draw(dc,"REVIEW REMAINING COMPLETION MARKS",790,856,19,kInk,true);
    return;
  }
  const auto found=BossMetadata().find(target->boss);
  if(found==BossMetadata().end()) return;
  DrawAssetFit(dc,found->second.asset,Rect(450,842,650,1008),false);
  g_font.Draw(dc,Upper(found->second.name),865,856,25,kInk,true);
  const auto lines=g_font.Wrap(target->requirement,19,440);
  for(size_t i=0;i<lines.size() && i<3;i++) g_font.Draw(dc,lines[i],675,903+i*23,19,kInk);
}

void DrawRunInfoPage(HDC dc) {
  DrawGoal(dc);
  const RunInfo& run = g_state.run;
  const PlayerMetadata* player = FindPlayer(g_state.character_id);
  const std::string character = Clean(g_state.character_name,
                                      player ? player->name : "UNKNOWN");
  DrawCharacterStickyNote(dc, player, character);

  const std::array<std::pair<std::string, std::string>, 4> details = {{
      {"SEED", Upper(run.seed.empty() ? "---- ----" : run.seed)},
      {"DEPTH", Number(run.stage)},
      {"TIME", RunTimer()},
      {"TOTAL HITS", g_state.progress.total_hits < 0 ? "--" : Number(g_state.progress.total_hits)},
  }};
  for (size_t index = 0; index < details.size(); ++index) {
    const float y = 600.0f + static_cast<float>(index) * 46.0f;
    g_font.Draw(dc, details[index].first, 480.0f, y, 27.0f, kInk);
    g_font.Draw(dc, details[index].second, 920.0f, y, 27.0f, kInk, true);
  }

}

bool ProgressBit(const std::string& bits, int id) {
  return id >= 0 && static_cast<size_t>(id)<bits.size() && bits[id]=='1';
}

void DrawProgressPage(HDC dc) {
  const auto& progress=g_state.progress;
  if(!progress.received) {
    g_font.Draw(dc,"WAITING FOR SAVE PROGRESS",620,680,26,kInk,true); return;
  }
  const char* tabs[]={"REMAINING", "COMPLETED", "CHALLENGES", "BOSSES", "MARKS"};
  for(int i=0;i<5;i++) {
    if(i==g_progress_tab) Frame(dc,Rect(100+i*208,548,302+i*208,592),kInk,2);
    g_font.Draw(dc,tabs[i],201.0f+i*208,557,18,kInk,true);
  }
  if(g_progress_tab==4) {
    const int character=g_progress_character<0 ? std::clamp(g_state.character_id,0,40) : g_progress_character;
    const auto* player=FindPlayer(character);
    g_font.Draw(dc,player ? Upper(player->name) : "CHARACTER",620,618,25,kInk,true);
    if(progress.completion.size()!=492) {
      g_font.Draw(dc,"WAITING FOR COMPLETION MARKS",620,720,22,kInk,true); return;
    }
    const char* names[]={"MOM'S HEART","ISAAC","SATAN","BOSS RUSH","???","THE LAMB","MEGA SATAN","GREED","HUSH","DELIRIUM","MOTHER","THE BEAST"};
    for(int i=0;i<12;i++) {
      int value=progress.completion[character*12+i];
      const char* status=(value&2) ? "HARD" : (value&1) ? "NORMAL" : (value&8) ? "ONLINE HARD" : (value&4) ? "ONLINE NORMAL" : "INCOMPLETE";
      const float x=270.0f+(i%3)*350, y=682.0f+(i/3)*65;
      g_font.Draw(dc,names[i],x,y,21,kInk,true);
      g_font.Draw(dc,status,x,y+27,17,value ? kInk : kInkMuted,true);
    }
    g_font.Draw(dc,"< CHARACTER",240,963,22,kInk,true);
    g_font.Draw(dc,"CHARACTER >",1000,963,22,kInk,true);
    return;
  }
  const auto& metadata=g_progress_tab==2 ? ChallengeMetadata() : g_progress_tab==3 ? BossMetadata() : AchievementMetadata();
  const auto& bits=g_progress_tab==2 ? progress.challenges : g_progress_tab==3 ? progress.bosses : progress.achievements;
  std::vector<const ProgressMetadata*> entries;
  int completed=0;
  for(const auto& pair:metadata) {
    const bool done=ProgressBit(bits,pair.first);
    if(done) ++completed;
    if((g_progress_tab==0 && done) || (g_progress_tab==1 && !done)) continue;
    entries.push_back(&pair.second);
  }
  g_font.Draw(dc,Number(completed)+" COMPLETED / "+Number(static_cast<int>(metadata.size())-completed)+" REMAINING",620,605,21,kInk,true);
  g_progress_offset=std::clamp(g_progress_offset,0,std::max(0,static_cast<int>(entries.size())-4));
  if(progress.readonly) g_font.Draw(dc,"UNLOCKS RESTRICTED FOR THIS RUN",620,630,16,kWine,true);
  for(int i=0;i<4 && g_progress_offset+i<static_cast<int>(entries.size());i++) {
    const auto& entry=*entries[g_progress_offset+i];
    const float y=652.0f+i*72;
    const std::string prefix=ProgressBit(bits,entry.id) ? "[X] " : "[ ] ";
    auto names=g_font.Wrap(prefix+entry.name,21,1010);
    if(!names.empty()) g_font.Draw(dc,names.front(),115,y,21,kInk);
    auto lines=g_font.Wrap(entry.requirement,17,1010);
    for(size_t line=0;line<std::min<size_t>(2,lines.size());line++)
      g_font.Draw(dc,lines[line],150,y+26+line*19,17,kInkMuted);
  }
  g_font.Draw(dc,"< PREVIOUS",240,963,23,kInk,true);
  g_font.Draw(dc,"NEXT >",1000,963,23,kInk,true);
}

void DrawWaiting(HDC dc) {
  const ULONGLONG now = GetTickCount64();
  const bool connected = Connected(now);
  // Update callbacks stop while paused. Keep the last authoritative snapshot
  // readable; silence is not a run-exit or a reason to cover the interface.
  if (g_state.in_run) return;
  // Keep startup messaging inside the inset map panel so the top regions
  // remain readable and every overlay respects the authored boundaries.
  AlphaFill(dc, Rect(42, 484, 1198, 1022), 184, kBackground);
  const char* heading = nullptr;
  const char* detail = nullptr;
  if (!g_state.last_packet_at) {
    heading = "ISAAC IS LOADING";
    detail = "CONNECTING LIVE RUN DATA";
  } else if (!connected) {
    heading = "GAME DATA PAUSED";
    detail = "RECONNECTING TO THE RUN";
  } else {
    heading = "START OR CONTINUE A RUN";
    detail = "NATIVE GAME PATCH CONNECTED";
  }
  g_font.Draw(dc, heading, kWidth / 2.0f, 690.0f, 44.0f,
              RGB(224, 201, 151), true);
  g_font.Draw(dc, detail, kWidth / 2.0f, 754.0f, 25.0f,
              RGB(198, 171, 125), true);
}

void RenderNow() {
  if (!g_back.dc) return;
  const size_t page_index = PageIndex(g_page);
  const Surface& page_surface = g_static_pages[page_index];
  if (!page_surface.dc) return;
  BitBlt(g_back.dc, 0, 0, kWidth, kHeight, page_surface.dc, 0, 0, SRCCOPY);
  DrawDashboard(g_back.dc);
  DrawWaiting(g_back.dc);
  g_last_render_at = GetTickCount64();
  if (g_window) InvalidateRect(g_window, nullptr, FALSE);
}

void RequestRender(bool immediate = false) {
  if (!g_window) return;
  const ULONGLONG now = GetTickCount64();
  const ULONGLONG elapsed = now - g_last_render_at;
  if (immediate || elapsed >= kMinimumFrameIntervalMs) {
    if (g_render_timer_armed) {
      KillTimer(g_window, kRenderTimer);
      g_render_timer_armed = false;
    }
    RenderNow();
    return;
  }
  if (!g_render_timer_armed) {
    g_render_timer_armed =
        SetTimer(g_window, kRenderTimer,
                 static_cast<UINT>(kMinimumFrameIntervalMs - elapsed),
                 nullptr) != 0;
  }
}

void SetPage(Page page) {
  if (page == g_page) return;
  g_page = page;
  g_item_scroll=0;
  const char* name = g_page == Page::kStats
                         ? "stats"
                         : (g_page == Page::kRunInfo
                                ? "run-info"
                                : (g_page == Page::kItems ? "items" : "map"));
  Log(LogLevel::kInfo, "[Touch] dashboard page=%s", name);
  RequestRender(true);
}

void ChangePage(int direction) {
  int index = static_cast<int>(g_page) + direction;
  while (index < 0) index += kDashboardPageCount;
  SetPage(static_cast<Page>(index % kDashboardPageCount));
}

// Tapping an active item, card/pill or trinket on the top strip shows its
// description in the items page.
bool FocusHudSlot(float x, float y) {
  auto inside = [&](const RECT& r) {
    return x >= r.left - 8 && x < r.right + 8 && y >= r.top - 8 && y < r.bottom + 8;
  };
  std::string key;
  {
    std::array<const ActiveItem*, 2> entries = {};
    for (const ActiveItem& item : g_state.inventory.actives)
      if (item.slot >= 0 && item.slot < 2 && !entries[item.slot]) entries[item.slot] = &item;
    const auto slots = HudSlots(328, 612, entries[1] != nullptr);
    for (int i = 0; i < 2; ++i)
      if (entries[i] && inside(slots[i])) key = "c" + std::to_string(entries[i]->id);
  }
  {
    std::array<const PocketItem*, 2> entries = {};
    for (const PocketItem& item : g_state.inventory.pockets)
      if (item.slot >= 0 && item.slot < 2 && !entries[item.slot]) entries[item.slot] = &item;
    const auto slots = HudSlots(638, 911, entries[1] != nullptr);
    for (int i = 0; i < 2; ++i)
      if (entries[i] && inside(slots[i]))
        key = std::string(entries[i]->kind == PocketKind::kPill ? "p" : "k") +
              std::to_string(entries[i]->id);
  }
  {
    std::array<const Trinket*, 2> entries = {};
    for (const Trinket& item : g_state.inventory.trinkets)
      if (item.slot >= 0 && item.slot < 2 && !entries[item.slot]) entries[item.slot] = &item;
    const auto slots = HudSlots(922, 1197, entries[1] != nullptr);
    for (int i = 0; i < 2; ++i)
      if (entries[i] && inside(slots[i])) key = "t" + std::to_string(entries[i]->id);
  }
  if (key.empty()) return false;
  g_focus_key = key;
  if (g_page != Page::kItems) {
    g_page = Page::kItems;
    g_item_scroll = 0;
  }
  RequestRender(true);
  return true;
}

// Selects the most recently obtained collectible so its description is on
// screen without any touch.
void FollowNewestItem() {
  const auto& held = g_state.inventory.collectibles;
  std::vector<int> ids;
  ids.reserve(held.size());
  for (const CollectedItem& entry : held) ids.push_back(entry.id);
  int newest = -1;
  if (g_known_collectibles.empty()) {
    if (!g_state.progress.history.empty()) {
      for (auto it = g_state.progress.history.rbegin(); it != g_state.progress.history.rend(); ++it)
        if (std::find(ids.begin(), ids.end(), it->id) != ids.end()) { newest = it->id; break; }
    }
    if (newest < 0 && !ids.empty()) newest = ids.back();
  } else {
    for (int id : ids)
      if (std::find(g_known_collectibles.begin(), g_known_collectibles.end(), id) ==
          g_known_collectibles.end())
        newest = id;
  }
  g_known_collectibles = std::move(ids);
  if (newest > 0) {
    g_selected_item = newest;
    g_focus_key.clear();
    if (g_page == Page::kItems) {
      // Scroll the grid so the new item is visible.
      for (size_t index = 0; index < held.size(); ++index) {
        if (held[index].id != newest) continue;
        const float row_top = static_cast<float>(index / kItemColumns) * kItemRowStep;
        if (row_top < g_item_scroll || row_top + kItemSlotSize > g_item_scroll + kItemGridHeight)
          g_item_scroll = std::clamp(row_top - kItemRowStep * (kItemVisibleRows - 1), 0.0f,
                                     MaximumItemScroll());
      }
    }
  }
  // A dropped trinket/card must not keep a stale description on screen.
  if (!g_focus_key.empty()) {
    ShownDescription shown;
    if (!ResolveDescription(&shown) || !shown.held) g_focus_key.clear();
  }
}

void HandleTap(float normalized_x, float normalized_y) {
  if (!std::isfinite(normalized_x) || !std::isfinite(normalized_y) ||
      normalized_x < 0.0f || normalized_x > 1.0f ||
      normalized_y < 0.0f || normalized_y > 1.0f)
    return;
  const float x = normalized_x * kWidth;
  const float y = normalized_y * kHeight;
  if (y < 350.0f && x > 320.0f) {
    if (FocusHudSlot(x, y)) return;
  }
  if (g_page == Page::kProgress) {
    if (y >= 550 && y < 604) { g_progress_tab=std::clamp(static_cast<int>((x-100)/208),0,4); g_progress_offset=0; }
    else if (y >= 950) {
      if(g_progress_tab==4) {
        if(g_progress_character<0) g_progress_character=std::clamp(g_state.character_id,0,40);
        g_progress_character=(g_progress_character+(x<620 ? 40 : 1))%41;
      } else g_progress_offset=std::max(0,g_progress_offset+(x<620 ? -4 : 4));
    }
    RequestRender(true); return;
  }
  if (!ItemPage()) return;
  if (y >= kItemGridTop && y < kItemGridTop + kItemGridHeight && x < 205.0f) {
    g_item_scroll = std::clamp(
        g_item_scroll - kItemVisibleRows * kItemRowStep, 0.0f,
        MaximumItemScroll());
    RequestRender(true);
    return;
  }
  if (y >= kItemGridTop && y < kItemGridTop + kItemGridHeight && x > 1025.0f) {
    g_item_scroll = std::clamp(
        g_item_scroll + kItemVisibleRows * kItemRowStep, 0.0f,
        MaximumItemScroll());
    RequestRender(true);
    return;
  }
  if (x < kItemGridLeft || x >= kItemGridLeft + kItemGridWidth ||
      y < kItemGridTop ||
      y >= kItemGridTop + kItemGridHeight)
    return;
  const int column = static_cast<int>((x - kItemGridLeft) / kItemColumnStep);
  if (column < 0 || column >= kItemColumns ||
      x - (kItemGridLeft + column * kItemColumnStep) >= kItemSlotSize)
    return;
  const int row = static_cast<int>((y - kItemGridTop + g_item_scroll) /
                                    kItemRowStep);
  if (y - kItemGridTop + g_item_scroll - row * kItemRowStep >= kItemSlotSize)
    return;
  const size_t index = static_cast<size_t>(row * kItemColumns + column);
  if (index < DisplayItems().size()) {
    g_selected_item = DisplayItems()[index].id;
    g_focus_key.clear();
    RequestRender(true);
  }
}

void HandleInput(const PanelInput& input) {
  switch (input.kind) {
    case PanelInputKind::kTap:
    case PanelInputKind::kHold:
      HandleTap(input.x, input.y);
      return;
    case PanelInputKind::kPageNext:
    case PanelInputKind::kNavRight:
      ChangePage(1);
      return;
    case PanelInputKind::kPagePrevious:
    case PanelInputKind::kNavLeft:
      ChangePage(-1);
      return;
    case PanelInputKind::kNavUp:
    case PanelInputKind::kNavDown:
      if (ItemPage()) {
        const float direction = input.kind == PanelInputKind::kNavUp ? -1.0f
                                                                     : 1.0f;
        g_item_scroll = std::clamp(g_item_scroll + direction * kItemRowStep,
                                   0.0f, MaximumItemScroll());
        RequestRender(true);
      }
      return;
    case PanelInputKind::kNavActivate:
      if (ItemPage() && g_selected_item < 0 &&
          !DisplayItems().empty()) {
        g_selected_item = DisplayItems().front().id;
        RequestRender(true);
      }
      return;
    case PanelInputKind::kDown:
      g_pointer_down = true;
      g_pointer_scrolling = false;
      g_pointer_start_x = input.x;
      g_pointer_start_y = input.y;
      g_pointer_last_y = input.y;
      return;
    case PanelInputKind::kMove:
      if (!g_pointer_down) return;
      if (ItemPage() &&
          (std::fabs(input.y - g_pointer_start_y) > 0.012f ||
           g_pointer_scrolling)) {
        g_pointer_scrolling = true;
        g_item_scroll = std::clamp(
            g_item_scroll - (input.y - g_pointer_last_y) * kHeight, 0.0f,
            MaximumItemScroll());
        g_pointer_last_y = input.y;
        RequestRender();
      }
      return;
    case PanelInputKind::kUp: {
      if (!g_pointer_down) return;
      const float dx = input.x - g_pointer_start_x;
      const float dy = input.y - g_pointer_start_y;
      const bool was_scrolling = g_pointer_scrolling;
      g_pointer_down = false;
      g_pointer_scrolling = false;
      if (std::fabs(dx) >= 0.18f && std::fabs(dx) > std::fabs(dy)) {
        ChangePage(dx < 0.0f ? 1 : -1);
      } else if (!was_scrolling && std::sqrt(dx * dx + dy * dy) <= 0.025f) {
        HandleTap(input.x, input.y);
      }
      return;
    }
    case PanelInputKind::kCancel:
      g_pointer_down = false;
      g_pointer_scrolling = false;
      return;
    default:
      return;
  }
}

void PollTouchSocket() {
  if (g_touch_socket == INVALID_SOCKET) return;
  for (int packet = 0; packet < 32; ++packet) {
    char bytes[256] = {};
    sockaddr_in sender = {};
    int sender_size = sizeof(sender);
    const int received = recvfrom(g_touch_socket, bytes, sizeof(bytes), 0,
                                  reinterpret_cast<sockaddr*>(&sender),
                                  &sender_size);
    if (received == SOCKET_ERROR) {
      if (WSAGetLastError() != WSAEWOULDBLOCK) {
        LogRateLimited("touch-recv", 5000, LogLevel::kWarning,
                       "[Touch] receive failed error=%d", WSAGetLastError());
      }
      return;
    }
    PanelInput input;
    if (ParsePanelInput(bytes, static_cast<size_t>(received), &input))
      HandleInput(input);
  }
}

void PollStateSocket() {
  if (g_state_socket == INVALID_SOCKET) return;
  bool accepted = false;
  PacketTopic newest_topic = PacketTopic::kNone;
  for (int packet = 0; packet < 64; ++packet) {
    std::array<char, 65507> bytes = {};
    sockaddr_in sender = {};
    int sender_size = sizeof(sender);
    const int received = recvfrom(g_state_socket, bytes.data(),
                                  static_cast<int>(bytes.size()), 0,
                                  reinterpret_cast<sockaddr*>(&sender),
                                  &sender_size);
    if (received == SOCKET_ERROR) {
      if (WSAGetLastError() != WSAEWOULDBLOCK) {
        LogRateLimited("state-recv", 5000, LogLevel::kWarning,
                       "[State] receive failed error=%d", WSAGetLastError());
      }
      break;
    }
    PacketTopic topic = PacketTopic::kNone;
    if (ApplyStatePacket(bytes.data(), static_cast<size_t>(received), &g_state,
                         &topic)) {
      accepted = true;
      newest_topic = topic;
    } else {
      LogRateLimited("state-invalid", 5000, LogLevel::kWarning,
                     "[State] rejected packet bytes=%d", received);
    }
  }
  if (!accepted) return;
  if (!g_logged_first_state) {
    g_logged_first_state = true;
    Log(LogLevel::kInfo,
        "[State] first authoritative in-game packet accepted sequence=%llu",
        static_cast<unsigned long long>(g_state.last_sequence));
  }
  FollowNewestItem();
  if (g_selected_item < 0 && !DisplayItems().empty())
    g_selected_item = DisplayItems().front().id;
  g_item_scroll = std::clamp(g_item_scroll, 0.0f, MaximumItemScroll());
  (void)newest_topic;
  RequestRender();
}

void PollDirectStatePackets() {
  std::deque<std::string> packets;
  AcquireSRWLockExclusive(&g_direct_state_lock);
  packets.swap(g_direct_state_packets);
  ReleaseSRWLockExclusive(&g_direct_state_lock);

  bool accepted = false;
  PacketTopic newest_topic = PacketTopic::kNone;
  for (const std::string& packet : packets) {
    PacketTopic topic = PacketTopic::kNone;
    if (ApplyStatePacket(packet.data(), packet.size(), &g_state, &topic)) {
      accepted = true;
      newest_topic = topic;
    } else {
      LogRateLimited("state-direct-invalid", 5000, LogLevel::kWarning,
                     "[State] rejected direct packet bytes=%u",
                     static_cast<unsigned int>(packet.size()));
    }
  }
  if (!accepted) return;
  if (!g_logged_first_state) {
    g_logged_first_state = true;
    Log(LogLevel::kInfo,
        "[State] first authoritative in-game packet accepted sequence=%llu",
        static_cast<unsigned long long>(g_state.last_sequence));
  }
  FollowNewestItem();
  if (g_selected_item < 0 && !DisplayItems().empty())
    g_selected_item = DisplayItems().front().id;
  g_item_scroll = std::clamp(g_item_scroll, 0.0f, MaximumItemScroll());
  (void)newest_topic;
  RequestRender();
}

SOCKET OpenSocket(unsigned short port, UINT message) {
  SOCKET socket_handle = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (socket_handle == INVALID_SOCKET) return INVALID_SOCKET;
  u_long nonblocking = 1;
  if (ioctlsocket(socket_handle, FIONBIO, &nonblocking) == SOCKET_ERROR) {
    closesocket(socket_handle);
    return INVALID_SOCKET;
  }
  sockaddr_in address = {};
  address.sin_family = AF_INET;
  address.sin_port = htons(port);
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if (bind(socket_handle, reinterpret_cast<sockaddr*>(&address),
           sizeof(address)) == SOCKET_ERROR ||
      WSAAsyncSelect(socket_handle, g_window, message, FD_READ | FD_CLOSE) ==
          SOCKET_ERROR) {
    closesocket(socket_handle);
    return INVALID_SOCKET;
  }
  return socket_handle;
}

void CloseSocket(SOCKET* socket_handle) {
  if (!socket_handle || *socket_handle == INVALID_SOCKET) return;
  WSAAsyncSelect(*socket_handle, g_window, 0, 0);
  closesocket(*socket_handle);
  *socket_handle = INVALID_SOCKET;
}

BOOL CALLBACK FindSecondary(HMONITOR monitor, HDC, LPRECT, LPARAM parameter);

LRESULT CALLBACK WindowProcedure(HWND window, UINT message, WPARAM wparam,
                                 LPARAM lparam) {
  switch (message) {
    case WM_DISPLAYCHANGE:
    case WM_POWERBROADCAST: {
      if(message==WM_POWERBROADCAST && wparam!=PBT_APMRESUMEAUTOMATIC && wparam!=PBT_APMRESUMESUSPEND) return TRUE;
      MonitorChoice monitor;
      EnumDisplayMonitors(nullptr,nullptr,FindSecondary,reinterpret_cast<LPARAM>(&monitor));
      if(monitor.score>=0) SetWindowPos(window,HWND_TOPMOST,monitor.bounds.left,monitor.bounds.top,
          kWidth,kHeight,SWP_NOACTIVATE|SWP_SHOWWINDOW);
      RequestRender(true);
      return TRUE;
    }
    case WM_ERASEBKGND:
      return 1;
    case WM_MOUSEACTIVATE:
      return MA_NOACTIVATE;
    case WM_NCHITTEST:
      return HTCLIENT;
    case WM_LBUTTONDOWN:
      SetCapture(window);
      HandleInput(PanelInput{PanelInputKind::kDown,
                             GET_X_LPARAM(lparam) / static_cast<float>(kWidth),
                             GET_Y_LPARAM(lparam) / static_cast<float>(kHeight),
                             0.0f});
      return 0;
    case WM_MOUSEMOVE:
      if (wparam & MK_LBUTTON) {
        HandleInput(PanelInput{
            PanelInputKind::kMove,
            std::clamp(GET_X_LPARAM(lparam) / static_cast<float>(kWidth), 0.0f,
                       1.0f),
            std::clamp(GET_Y_LPARAM(lparam) / static_cast<float>(kHeight),
                       0.0f, 1.0f),
            0.0f});
      }
      return 0;
    case WM_LBUTTONUP:
      ReleaseCapture();
      HandleInput(PanelInput{PanelInputKind::kUp,
                             GET_X_LPARAM(lparam) / static_cast<float>(kWidth),
                             GET_Y_LPARAM(lparam) / static_cast<float>(kHeight),
                             0.0f});
      return 0;
    case WM_TIMER:
      if (wparam == kRenderTimer) {
        KillTimer(window, kRenderTimer);
        g_render_timer_armed = false;
        RenderNow();
      } else if (wparam == kHealthTimer) {
        const bool connected = Connected();
        if (connected != g_was_connected) {
          g_was_connected = connected;
          RequestRender(true);
        } else if (g_page == Page::kRunInfo && connected && g_state.in_run) {
          RequestRender();
        }
      }
      return 0;
    case kTouchMessage:
      if (WSAGETSELECTEVENT(lparam) == FD_READ && !WSAGETSELECTERROR(lparam))
        PollTouchSocket();
      return 0;
    case kStateMessage:
      if (WSAGETSELECTEVENT(lparam) == FD_READ && !WSAGETSELECTERROR(lparam))
        PollStateSocket();
      return 0;
    case kDirectStateMessage:
      PollDirectStatePackets();
      return 0;
    case WM_PAINT: {
      PAINTSTRUCT paint = {};
      HDC dc = BeginPaint(window, &paint);
      const int width = paint.rcPaint.right - paint.rcPaint.left;
      const int height = paint.rcPaint.bottom - paint.rcPaint.top;
      if (width > 0 && height > 0 && g_back.dc) {
        BitBlt(dc, paint.rcPaint.left, paint.rcPaint.top, width, height,
               g_back.dc, paint.rcPaint.left, paint.rcPaint.top, SRCCOPY);
      }
      EndPaint(window, &paint);
      return 0;
    }
    case WM_PRINTCLIENT:
      if (wparam && g_back.dc) {
        BitBlt(reinterpret_cast<HDC>(wparam), 0, 0, kWidth, kHeight,
               g_back.dc, 0, 0, SRCCOPY);
      }
      return 0;
    case WM_CLOSE:
      ShowWindow(window, SW_HIDE);
      return 0;
    case kShutdownMessage:
      DestroyWindow(window);
      return 0;
    case WM_DESTROY:
      PostQuitMessage(0);
      return 0;
    default:
      return DefWindowProcW(window, message, wparam, lparam);
  }
}

BOOL CALLBACK FindSecondary(HMONITOR monitor, HDC, LPRECT, LPARAM parameter) {
  auto* choice = reinterpret_cast<MonitorChoice*>(parameter);
  MONITORINFO information = {};
  information.cbSize = sizeof(information);
  if (!choice || !GetMonitorInfoW(monitor, &information) ||
      (information.dwFlags & MONITORINFOF_PRIMARY))
    return TRUE;
  const int width = information.rcMonitor.right - information.rcMonitor.left;
  const int height = information.rcMonitor.bottom - information.rcMonitor.top;
  int score = width == kWidth && height == kHeight ? 1000000
                                                   : width * height;
  if (information.rcMonitor.top >= 1080) score += 100000;
  if (score > choice->score) {
    choice->score = score;
    choice->bounds = information.rcMonitor;
  }
  return TRUE;
}

bool CoreAssetsReady() {
  const char* required[] = {
      "custom/background.png",
      "custom/sticky_note.png",
      "custom/item_slot_dark.png",
      "custom/item_description_panel.png",
      "game/font/upheaval_0.png",
      "game/gfx/items/pick ups/pickup_007_pill.png",
  };
  for (const char* path : required) {
    if (!ResolveAsset(path)) {
      Log(LogLevel::kError, "[Assets] required texture unavailable path=%s",
          path);
      return false;
    }
  }
  const bool pickups = FindSprite("hudpickups", "Idle", 0) != nullptr;
  const bool hearts = FindSprite("ui_hearts", "RedHeartFull") != nullptr;
  const bool map_icons = FindSprite("minimap_icons", "IconBoss") != nullptr;
  if (!pickups || !hearts || !map_icons) {
    Log(LogLevel::kError,
        "[Assets] required sprites unavailable pickups=%u hearts=%u map=%u",
        pickups ? 1u : 0u, hearts ? 1u : 0u, map_icons ? 1u : 0u);
  }
  return pickups && hearts && map_icons;
}

DWORD WINAPI PanelThread(void*) {
  Log(LogLevel::kInfo, "[PanelInit] thread entered tid=%lu",
      static_cast<unsigned long>(GetCurrentThreadId()));
  WNDCLASSEXW window_class = {};
  window_class.cbSize = sizeof(window_class);
  window_class.lpfnWndProc = WindowProcedure;
  window_class.hInstance = g_module;
  window_class.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  window_class.lpszClassName = L"IsaacSecondScreenPanel";
  if (!RegisterClassExW(&window_class)) {
    Log(LogLevel::kError, "[PanelInit] RegisterClassExW failed error=%lu",
        static_cast<unsigned long>(GetLastError()));
    return 1;
  }
  Log(LogLevel::kInfo, "[PanelInit] window class registered");

  MonitorChoice monitor;
  EnumDisplayMonitors(nullptr, nullptr, FindSecondary,
                      reinterpret_cast<LPARAM>(&monitor));
  Log(LogLevel::kInfo,
      "[PanelInit] monitor selected position=%ld,%ld size=%ldx%ld",
      monitor.bounds.left, monitor.bounds.top,
      monitor.bounds.right - monitor.bounds.left,
      monitor.bounds.bottom - monitor.bounds.top);
  g_window = CreateWindowExW(
      WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
      window_class.lpszClassName,
      L"The Binding of Isaac: Repentance+ Dual-Screen", WS_POPUP,
      monitor.bounds.left, monitor.bounds.top, kWidth, kHeight, nullptr,
      nullptr, g_module, nullptr);
  if (!g_window) {
    Log(LogLevel::kError, "[PanelInit] CreateWindowExW failed error=%lu",
        static_cast<unsigned long>(GetLastError()));
    UnregisterClassW(window_class.lpszClassName, g_module);
    return 2;
  }
  Log(LogLevel::kInfo, "[PanelInit] native window created");
  HDC window_dc = GetDC(g_window);
  bool surfaces_ready = CreateSurface(window_dc, &g_back);
  for (size_t index = 0; index < kDashboardPageCount; ++index)
    surfaces_ready = CreateSurface(window_dc, &g_static_pages[index]) &&
                     surfaces_ready;
  ReleaseDC(g_window, window_dc);
  Log(LogLevel::kInfo, "[PanelInit] DIB surfaces ready=%u",
      surfaces_ready ? 1u : 0u);
  const bool asset_cache_ready = InitializeAssetCache(g_module);
  Log(LogLevel::kInfo, "[PanelInit] asset cache ready=%u",
      asset_cache_ready ? 1u : 0u);
  if (!surfaces_ready || !asset_cache_ready) {
    Log(LogLevel::kError, "[Panel] DIB or GDI+ initialization failed");
    DestroyWindow(g_window);
    g_window = nullptr;
    for (Surface& surface : g_static_pages) DestroySurface(&surface);
    DestroySurface(&g_back);
    UnregisterClassW(window_class.lpszClassName, g_module);
    return 3;
  }

  const std::wstring catalog_path = AssetRootPath() + L"catalog.tsv";
  const std::wstring font_path =
      AssetRootPath() + L"game\\font\\upheaval.fnt";
  const bool catalog_ready = LoadAssetCatalog(catalog_path.c_str());
  Log(LogLevel::kInfo, "[PanelInit] catalog ready=%u",
      catalog_ready ? 1u : 0u);
  const bool font_ready =
      g_font.Load(font_path.c_str(), "game/font/upheaval_0.png");
  Log(LogLevel::kInfo, "[PanelInit] font ready=%u", font_ready ? 1u : 0u);
  const bool core_assets_ready = CoreAssetsReady();
  Log(LogLevel::kInfo, "[PanelInit] core assets ready=%u",
      core_assets_ready ? 1u : 0u);
  if (!catalog_ready || !font_ready || !core_assets_ready) {
    Log(LogLevel::kError,
        "[Panel] authentic asset validation failed catalog=%u font=%u core=%u",
        catalog_ready ? 1u : 0u, font_ready ? 1u : 0u,
        core_assets_ready ? 1u : 0u);
    DestroyWindow(g_window);
    g_window = nullptr;
    g_font.Clear();
    ClearAssetCatalog();
    ShutdownAssetCache();
    for (Surface& surface : g_static_pages) DestroySurface(&surface);
    DestroySurface(&g_back);
    UnregisterClassW(window_class.lpszClassName, g_module);
    return 4;
  }
  Log(LogLevel::kInfo, "[Assets] catalog=%s records-ready=1",
      CatalogSourceBuild().c_str());
  Log(LogLevel::kInfo, "[PanelInit] drawing dashboard backgrounds");
  DrawStaticPage(Page::kMap, g_static_pages[PageIndex(Page::kMap)].dc);
  DrawStaticPage(Page::kStats, g_static_pages[PageIndex(Page::kStats)].dc);
  DrawStaticPage(Page::kRunInfo, g_static_pages[PageIndex(Page::kRunInfo)].dc);
  DrawStaticPage(Page::kItems, g_static_pages[PageIndex(Page::kItems)].dc);
  DrawStaticPage(Page::kPickups, g_static_pages[PageIndex(Page::kPickups)].dc);
  DrawStaticPage(Page::kProgress, g_static_pages[PageIndex(Page::kProgress)].dc);
  Log(LogLevel::kInfo, "[PanelInit] dashboard backgrounds ready");

  WSADATA data = {};
  if (WSAStartup(MAKEWORD(2, 2), &data) == 0) {
    g_winsock = true;
    g_touch_socket = OpenSocket(kTouchPort, kTouchMessage);
    g_state_socket = OpenSocket(kStatePort, kStateMessage);
  }
  if (g_touch_socket == INVALID_SOCKET) {
    Log(LogLevel::kWarning, "[Touch] listener unavailable port=%u",
        kTouchPort);
  }
  if (g_state_socket == INVALID_SOCKET) {
    Log(LogLevel::kError, "[State] listener unavailable port=%u", kStatePort);
  }
  SetTimer(g_window, kHealthTimer, 1000, nullptr);
  RenderNow();
  ShowWindow(g_window, SW_SHOWNOACTIVATE);
  SetWindowPos(g_window, HWND_TOPMOST, monitor.bounds.left, monitor.bounds.top,
               kWidth, kHeight, SWP_NOACTIVATE | SWP_SHOWWINDOW);
  Log(LogLevel::kInfo,
      "[Panel] ready class=IsaacSecondScreenPanel title=The Binding of "
      "Isaac: Repentance+ Dual-Screen position=%ld,%ld size=%dx%d "
      "touch=%u state=%u build=%s",
      monitor.bounds.left, monitor.bounds.top, kWidth, kHeight, kTouchPort,
      kStatePort, g_build_label.c_str());

  MSG message = {};
  while (GetMessageW(&message, nullptr, 0, 0) > 0) {
    TranslateMessage(&message);
    DispatchMessageW(&message);
  }

  if (g_render_timer_armed) KillTimer(g_window, kRenderTimer);
  KillTimer(g_window, kHealthTimer);
  g_render_timer_armed = false;
  CloseSocket(&g_touch_socket);
  CloseSocket(&g_state_socket);
  if (g_winsock) WSACleanup();
  g_winsock = false;
  g_font.Clear();
  ClearAssetCatalog();
  ShutdownAssetCache();
  for (Surface& surface : g_static_pages) DestroySurface(&surface);
  DestroySurface(&g_back);
  g_window = nullptr;
  UnregisterClassW(window_class.lpszClassName, g_module);
  return 0;
}

}  // namespace

bool StartPanel(HMODULE module, const char* build_label) {
  if (g_thread || !module) return g_thread != nullptr;
  g_module = module;
  g_build_label = build_label ? build_label : "";
  g_thread = CreateThread(nullptr, 0, PanelThread, nullptr, 0, &g_thread_id);
  if (g_thread) {
    // The panel performs a short burst of startup I/O and composition. Leaving
    // it at the process default priority avoids starvation under Wine while
    // Isaac's worker pool is loading its large archives.
    Log(LogLevel::kInfo, "[PanelInit] thread created tid=%lu",
        static_cast<unsigned long>(g_thread_id));
  }
  return g_thread != nullptr;
}

void StopPanel() {
  if (g_window) PostMessageW(g_window, kShutdownMessage, 0, 0);
  if (g_thread) {
    WaitForSingleObject(g_thread, 2500);
    CloseHandle(g_thread);
  }
  g_thread = nullptr;
  g_thread_id = 0;
  AcquireSRWLockExclusive(&g_direct_state_lock);
  g_direct_state_packets.clear();
  ReleaseSRWLockExclusive(&g_direct_state_lock);
}

bool SubmitStatePacket(const char* packet, size_t bytes) {
  if (!packet || bytes == 0 || bytes > 65507) return false;
  HWND window = g_window;
  if (!window || !IsWindow(window)) return false;

  AcquireSRWLockExclusive(&g_direct_state_lock);
  if (g_direct_state_packets.size() >= 64) g_direct_state_packets.pop_front();
  g_direct_state_packets.emplace_back(packet, bytes);
  ReleaseSRWLockExclusive(&g_direct_state_lock);
  return PostMessageW(window, kDirectStateMessage, 0, 0) != FALSE;
}

}  // namespace isaacds
