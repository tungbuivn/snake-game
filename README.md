# Snake 2D — Android

Game rắn săn mồi 2D viết bằng Java thuần (Android framework, không dùng AndroidX/Gradle),
vẽ trên `SurfaceView` với render thread riêng và nội suy chuyển động từng khung hình.

**Tải APK:** [bản phát hành mới nhất](https://github.com/tungbuivn/snake-game/releases/latest)
(`snake-2d.apk`, cài trực tiếp, không cần root).

| Menu 4 chế độ | Chế độ chơi theo waypoint |
| --- | --- |
| ![menu](docs/menu.png) | ![waypoints](docs/waypoints.png) |

## Tính năng

- **4 chế độ**, 4 nút xếp **theo hàng dọc** trên màn hình bắt đầu:
  1. `CHƠI` — **đi theo waypoint**: rắn đứng yên chờ; chạm lên board để đặt waypoint
     (chạm vào waypoint có sẵn để chọn/sửa), hiện **4 phím mũi tên + nút ✕** để dịch
     điểm hoặc xoá; có **đường preview nét đứt mờ** (đi ngang trước, rồi dọc) và nút
     **`MOVE`** để rắn chạy theo route (đang chạy thì nút đổi thành `DỪNG`); hết waypoint
     thì rắn tự đứng lại. Chưa bấm `MOVE` thì rắn **không di chuyển**.
     Nút **`PREVIEW`** xem lại route **hiện tại** như một đoạn video (bóng xanh cyan chạy
     trên route, rắn thật đứng yên); thoát replay là chơi tiếp bình thường.
     Nếu **route chạy qua một waypoint khác** (đi ngang qua điểm vàng/điểm khác trước khi
     tới lượt) thì app đánh dấu **`BROKEN`**: dấu ✕ đỏ ngay trên waypoint đó + dòng cảnh
     báo hồng ở thanh dưới (`BROKEN tại #3,#5`) để bạn đổi lại thứ tự.
     Nếu **route đâm vào thân rắn** (mô phỏng cả đuôi di chuyển), app đánh dấu
     **`COLLISION`**: ✕ đỏ + vòng tròn đỏ ngay ô sẽ đâm, kèm dòng hồng
     `COLLISION: route đâm thân ở (x,y) • sửa waypoint`.
     Khi chọn 1 waypoint, app vẽ thêm **bóng rắn màu cam mờ**, **vẽ đè lên trên rắn thật**
     và **luôn đủ chiều dài thân**: đầu ở waypoint, thân đi ngược theo route; nếu route
     ngắn hơn thân thì phần còn lại lấy tiếp từ các đốt hiện có (đuôi thật) — nhờ vậy
     luôn thấy được **đầu và đuôi** sẽ ở đâu để đặt waypoint tiếp theo.
     Waypoint **được phép nằm trên thân rắn hiện tại** (khi rắn bò tới thì đuôi đã đi
     qua, ô đó thường đã trống).
     Nút **`FOLLOW`** (ô dưới-trái cụm phím): rắn **tự tìm đường bám theo đuôi** của mình;
     bấm 1 lần rồi chạm lên **một đốt trên thân** để chọn **điểm dừng** — rắn bám đuôi tới
     khi đầu tới đúng điểm đó thì dừng (bấm lần nữa để chạy bám đuôi không điểm dừng, hoặc
     bấm `STOP` để dừng tay).
  2. `AUTO WIN` — **một** chu trình Hamilton **cố định** phủ toàn board (boustrophedon),
     luôn thắng, 0 chết.
  3. `SHORT PATH` — **ăn quả nhưng phải còn đường bám đuôi**:
     1. BFS đường ngắn nhất tới quả; **bước kế tiếp** và **trạng thái sau khi ăn** (rắn ảo
        dài thêm 1 đốt) đều phải **còn đường tới đuôi** + **vùng trống đủ chỗ cho cả thân**
        → chỉ khi qua cả hai mới đi.
     2. Không qua thì thử lại với đường "dài" (zig-zag 2 ô). Vẫn không qua thì **không ăn
        vội**: cứ **bám đuôi** (đường zig-zag tới đuôi — luôn an toàn) cho tới khi tìm được
        phương án ăn an toàn khác.
     3. Cuối cùng: chọn ô trống giữ được đường tới đuôi với nhiều không gian nhất.
     Quả sinh **random**; nếu vẫn bị dồn vào thế bí thì cắt đuôi và chạy tiếp.
  4. `MANGO` — bản MangoSnake: **mỗi ván một chu trình Hamilton ngẫu nhiên** (250 lượt
     split + mend), cùng luật đi an toàn như `AUTO WIN`, quả sinh random trong cung →
     cũng **luôn thắng**. Đo 60s: 62 quả, avg 0.93s, 20 lượt cuối avg 0.60s, max 2.5s,
     100% ≤3s, **0 chết**, không kẹt.
- **Quả sinh ngẫu nhiên, mỗi quả một lần**: chọn 1 ô trống ngẫu nhiên (trong cung trống
  của chu trình → luôn tới được), **không sinh lại liên tục** nên quả không nhấp nháy.
- **Cắn thân = một lần chết nhưng không dừng ván**: khi đầu đâm vào thân (mọi chế độ),
  đoạn thân từ chỗ đâm tới đuôi bị cắt bỏ, bộ đếm **`Chết` tăng 1**, rắn **đổi màu**
  (6 màu luân phiên) và **chạy tiếp**; chỉ đâm tường mới kết thúc ván.
- **AI (AutoPilot)**: hai cách chạy, cùng một bộ máy an toàn (chỉ đi **tiến** trên chu
  trình, trong **cung trống** giữa đầu và đuôi; quả sinh trong cung nên luôn tới được).
  - **`AUTO WIN` — chu trình Hamilton đơn, cố định**: một chu trình boustrophedon phủ
    toàn board; mỗi ván đường đi y hệt nhau. Shortcut BFS trong cung để đi nhanh hơn,
    nước nào **vượt quá quả trên chu trình** thì bị loại (vượt là mất nguyên một vòng) —
    nhờ vậy đầu luôn tiến đều tới quả và ván **không bao giờ thua**.
  - **`MANGO` — chu trình Hamilton ngẫu nhiên**: cũng một chu trình đơn, nhưng chuỗi
    boustrophedon bị “xáo” bằng 250 lượt split + mend (2-opt) nên **mỗi ván một đường
    mê cung mới**; luật đi an toàn y như `AUTO WIN`.
  - Quả chỉ sinh ở ô trống **và còn tới được** (kiểm tra BFS lúc sinh, không sinh lại
    liên tục) nên quả không nhấp nháy mà cũng không bị kẹt sau thân rắn.
  Đo trên máy: **AUTO WIN** 58s: 70 quả, avg 0.85s, 20 lượt cuối avg 0.51s, max 2.8s,
  **100% ≤3s, 0 chết** · **MANGO** 60s: 62 quả, avg 0.93s, 100% ≤3s, 0 chết; chạy dài
  MANGO thắng **798/800 đốt** sau ~480s, 0 chết.
  *Bước tiến đảm bảo* (nhánh chu trình): nước đi **vượt qua quả trên chu trình** bị
  loại (vượt là mất thêm nguyên một vòng); chỉ khi mọi nước đều vượt mới chọn bước
  ngắn nhất — nhờ vậy đầu luôn tiến đều tới quả.

- **Gia tốc + ngân sách 3 giây + "đuổi kịp"**: `interval` tiến về đích với **giới hạn
  gia tốc 3000ms/s** (tăng/giảm tốc mượt). Đích mỗi khung = min(nền theo điểm,
  `3000ms / stepsToFood`) nhân hệ số thích nghi đo từ thời gian ăn thực tế, sàn 22ms.
  **Nếu quá 3s mà chưa ăn được**, đích tiếp tục giảm theo hàm mũ theo thời gian trễ
  (`×exp(-1.2·trễ)`), giới hạn gia tốc được mở (60000ms/s) và sàn hạ xuống **5ms** —
  rắn cứ tăng tốc dần cho tới khi ăn được. **Quá 2s chưa ăn** thì bật "teleport":
  mỗi khung hình mô phỏng nhiều bước (×1 → ×31 tuỳ độ trễ) nên rắn *tua nhanh* tới
  quả — vẫn đi đúng đường, chỉ bớt khung animation. Đo trên máy (5 phút): **0 lần
  chết**, 796 quả, trung bình 0.38s/quả, trung vị 0.17s, p90 0.78s, max 3.5s,
  **98% lượt ≤3s**.
- **Chiến thắng**: khi không còn ô trống để sinh quả (rắn phủ kín board), game vào
  trạng thái **CHIẾN THẮNG** (thẻ vàng, điểm cuối). Watch-mode tự chơi lại sau 4s.
  Đo trên máy: rắn đạt **800/800 đốt** sau 300s, `state=4`.
- **Rắn vẽ tối giản**: thân đứng yên trong mỗi tick — chỉ **đầu duỗi ra** ô kế tiếp và
  **đuôi thụt vào**; mọi điểm giữa nằm đúng tâm ô nên **hình học khúc cua cố định,
  vẽ một lần là không thể gợn/phập phồng**. Đường tâm là polyline, khớp cua do
  **ROUND join** tự bo bán kính = nửa bề rộng (không bo tay) nên bề dày luôn hằng số;
  **thân là MỘT nét vẽ màu phẳng** (`#35D07F`, không gradient/không vảy/không viền) →
  chỉ 1 lệnh vẽ cho cả thân; đầu là capsule to hơn thân (0.88×cell) có gradient, mắt
  (đồng tử + ánh sáng), lỗ mũi, lưỡi thè, mắt X khi thua; đuôi bo tròn đúng bằng bề
  rộng thân.
- HUD: pill `ĐIỂM` / `KỶ LỤC` cao 52dp, nhãn và số tách nhau ~7dp (không dính).
- Mồi táo có quầng sáng, nhấp nháy; hạt hiệu ứng khi ăn; điểm `+10` bay lên.
- Điểm số + kỷ lục lưu `SharedPreferences`, tạm dừng/tiếp tục, overlay kết thúc,
  rung nhẹ (haptic) khi ăn/thua.
- Board tự co giãn theo màn hình: 20 cột, số hàng 12..46. **Dải điều khiển luôn được
  chừa riêng ở dưới grid** (cụm phím waypoint + PREVIEW + `MOVE` + tạm dừng) nên bàn
  phím **không bao giờ đè lên ô chơi**; nhờ vậy máy 1440×3200 ra **20×34** (ô 68px) thay
  vì 20×40 trước đây — grid gọn hơn, chọn điểm dễ hơn. HUD nằm sát mép trên hai bên
  camera cutout. Fullscreen immersive.

## Cấu trúc

```
app/src/main/AndroidManifest.xml
app/src/main/java/com/dsh/snake/MainActivity.java   # fullscreen, lifecycle
app/src/main/java/com/dsh/snake/GameView.java       # game loop, vẽ, input, UI
app/src/main/java/com/dsh/snake/AutoPilot.java      # AI tự chơi
app/src/main/res/...                                # theme, icon vector/adaptive
build.ps1                                           # build APK không cần Gradle
tools/analyze.js, tools/verify.js                   # kiểm tra ảnh chụp màn hình
tools/mango_cycle.js, tools/sim_cycles.js           # kiểm chứng chu trình + mô phỏng AI
tools/eatspeed.js                                   # thống kê tốc độ ăn từ eats.txt
```

## Build & deploy

Yêu cầu: JDK 17+ (`JAVA_HOME`), Android SDK build-tools 35.0.0 + platform android-35,
`adb` với thiết bị đã bật USB debugging. `build.ps1` tìm SDK ở `.android-sdk/` trong repo
(không có thì tự tải về), nên clone mới vẫn build được.

```powershell
# chỉ build
powershell -ExecutionPolicy Bypass -File build.ps1

# build + cài qua adb + chạy
powershell -ExecutionPolicy Bypass -File build.ps1 -Deploy
```

Quy trình trong `build.ps1`:
`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `jar uf` (dex vào apk) →
`zipalign` → `apksigner` (keystore debug tự sinh ở `keystore/`).

APK: `build/snake-2d.apk` (~53 KB, ký bằng debug key — cài trực tiếp được, nhưng nếu
muốn phát hành chính thức trên Play Store thì thay keystore riêng).

> Lưu ý khi sửa file có tiếng Việt: phải đọc/ghi UTF-8. Dùng
> `Get-Content -Raw -Encoding utf8` + `Set-Content -Encoding utf8` (hoặc công cụ
> soạn thảo hỗ trợ UTF-8). Nếu đọc bằng encoding mặc định (ANSI) rồi ghi lại, toàn
> bộ chuỗi tiếng Việt sẽ bị mojibake (`ĐIỂM` → `ÄIá»‚M`). Có thể kiểm tra/binary
> bằng `tools/checktext.js` và sửa bằng `tools/repair_vn.js`.
>
> Lưu ý khi deploy bằng script/agent: adb server có thể bị kill khi process cha kết thúc.
> Nếu lệnh adb "treo", chạy `adb nodaemon server` trong một background job để giữ daemon,
> sau đó các lệnh adb khác sẽ nối vào daemon đó.

## Điều khiển

| Thao tác | Tác dụng |
| --- | --- |
| Chạm lên board (chế độ `CHƠI`) | Đặt waypoint mới; chạm vào waypoint có sẵn để chọn nó |
| 4 phím mũi tên / `✕` | Dịch waypoint đang chọn 1 ô / xoá waypoint đó |
| `AUTO` (giữa 4 mũi tên) | **Tự đặt waypoint an toàn**: lấy đường ngắn nhất đầu→quả (BFS, thân là tường), **mô phỏng ăn xong** rồi kiểm tra **vẫn còn đường bám đuôi**; chỉ khi qua mới đặt route (chỉ giữ **điểm gấp khúc** + quả). Không an toàn thì **tự chuyển sang route bám đuôi** để rắn không chết |
| `LIST` (đối xứng với `✕`) | Mở **hộp thoại danh sách waypoint**: mỗi dòng ghi số thứ tự + toạ độ ô; **kéo-thả dòng** để đổi thứ tự (thả tới đâu là chèn tới đó), chạm 1 dòng để chọn nó, chạm ra ngoài hoặc `✕` để đóng |
| `PREVIEW (n)` (dưới cụm phím) | **Playback route hiện tại như video**: rắn thật **đứng yên**, một bóng rắn **xanh cyan** bò lại đúng các waypoint **đang có** (không lưu lịch sử cũ), có nhãn `PREVIEW i / n`; nút đổi thành **`THOÁT PREVIEW`** — bấm lần nữa hoặc chạm bất kỳ đâu là thoát và **chơi tiếp** đúng chỗ đang đứng |
| `FOLLOW` (ô dưới-trái của cụm phím) | **Bám đuôi**: bấm 1 lần → *chờ chọn điểm* (chạm lên **một đốt trên thân rắn** để đặt điểm dừng); bấm lần nữa → chạy bám đuôi luôn. Rắn tự tìm đường (BFS) **chạy theo đuôi** và **luôn giữ khoảng cách ≥ 2 ô tới đuôi** (để lúc ăn quả, đuôi có dài ra thêm 1 đốt vẫn còn khoảng an toàn mà bám); dừng khi **đầu tới đúng điểm đã chọn**. Nút đổi thành **`STOP`** khi đang chạy |
| `MOVE` / `DỪNG` (thanh dưới) | Cho rắn chạy theo route / dừng lại để sửa |
| Menu tạm dừng | `TIẾP TỤC` để chạy tiếp; chạm **AUTO WIN / SHORT PATH / MANGO** = **bật lại AI cho đúng ván đang chơi** (giữ điểm) — kể cả khi bạn vừa vuốt/chạm badge để giành quyền điều khiển tay; nếu ván đang ở chế độ khác thì nút đó sẽ chơi lại ở chế độ mới |
| Vuốt 4 hướng (chế độ AI) | Chuyển sang tự điều khiển (rắn đứng chờ waypoint) |
| Chạm `CHƠI` / `AUTO WIN` / `SHORT PATH` / `MANGO` | Bắt đầu chế độ tương ứng (4 nút xếp dọc) |
| Chạm `❚❚` (góc dưới phải) | Tạm dừng / tiếp tục |
| Chạm khi thua | Chơi lại; menu 2×2 có `CHƠI LẠI` · `AUTO WIN` / `SHORT PATH` · `MANGO` |

Cụm phím chỉnh waypoint nằm trong **dải điều khiển dưới grid** (không đè lên bàn chơi):
`[[LIST] [▲] [✕]] / [[◀] [AUTO] [▶]] / [[FOLLOW] [▼]]` + nút `PREVIEW (n)` bên dưới,
còn `MOVE` và nút tạm dừng ở bên phải dải này; dòng gợi ý + `Chết: N` ở trên cùng của dải.

HUD hiện `ĐIỂM`, `KỶ LỤC`, badge tên chế độ AI, và thanh dưới có `Chết: N` — mỗi lần
**cắn vào thân** (bị cắt đuôi) hay **đâm tường** đều +1 và đổi màu rắn.

## Kiểm tra nhanh (tuỳ chọn)

App ghi snapshot trạng thái vào `Android/data/com.dsh.snake/files/state.txt`
(state, điểm, fps, quả, chu trình, toạ độ từng đốt và toạ độ vẽ đã nội suy) — dùng cho:

- `tools/eatspeed.js` — thống kê tốc độ ăn (avg/median/p90/max, % lượt ≤3s) từ `eats.txt`.
- `tools/sim_cycles.js` — mô phỏng 4 chế độ trên JS để so sánh các chu trình
  (boustrophedon / 2 dải / MangoSnake ngẫu nhiên, nhiều seed).
- `tools/mango_cycle.js` / `tools/CycleTest.java` — kiểm chứng chu trình ngẫu nhiên
  vẫn là **một chu trình Hamilton duy nhất** trên mọi kích thước board.
- `tools/widths.js` — đo bề rộng thân theo phương vuông góc tại từng đốt và so với
  hàm thuôn dự kiến, để chắc khúc cua không phình (kết quả: `bulges=0`).
- `tools/curvature.js` — tính bán kính cong nhỏ nhất của đường tâm so với nửa bề rộng
  thân (điều kiện đủ để ribbon không tự gấp ở cua).
- `tools/spacing.js` — kiểm tra khoảng cách giữa các đốt (phát hiện thân bị bóp khi cua).
- `tools/verify.js` — đối chiếu ảnh chụp với vị trí rắn thực tế (thân liền mạch).
