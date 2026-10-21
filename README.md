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
  1. `CHƠI` — **đi theo waypoint + tự tìm đường**: rắn đứng yên chờ; chạm lên board để
     đặt waypoint (chạm vào waypoint có sẵn để chọn/sửa), hiện **4 phím mũi tên + nút ✕**
     để dịch điểm hoặc xoá; có **đường preview nét đứt mờ** vẽ đúng đường sẽ đi và nút
     **`MOVE`** để rắn chạy theo route (đang chạy thì nút đổi thành `DỪNG`); hết waypoint
     thì rắn tự đứng lại. Chưa bấm `MOVE` thì rắn **không di chuyển**.
     **Chạm waypoint là đủ** — không phải tự vẽ từng bước: mỗi đoạn route được tìm bằng
     **BFS ngắn nhất coi thân rắn là tường** (đuôi đang bỏ đi nên tính là trống), đúng
     thuật toán của các chế độ AI, nên rắn **tự vòng qua thân mình** để tới waypoint;
     preview, bóng ma và cảnh báo `COLLISION` đều tính trên chính đường đó.
     Nút **`PREVIEW`** xem lại route **hiện tại** như một đoạn video (bóng xanh cyan chạy
     trên route, rắn thật đứng yên); thoát replay là chơi tiếp bình thường.
     Nếu **không có đường tới một waypoint** (bị thân rắn bịt kín) thì app đánh dấu
     **`BROKEN`**: dấu ✕ đỏ ngay trên waypoint đó + dòng cảnh báo hồng ở thanh dưới
     (`BROKEN tại #3,#5 • không có đường tới`) để bạn dời điểm hoặc đổi thứ tự.
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
  2. `AUTOPLAY` — AI đi **chu trình Hamilton** (boustrophedon cố định) phủ toàn board:
     mỗi tick rắn **chỉ đi tiến trên chu trình**, trong **cung trống giữa đầu và đuôi**;
     quả sinh trong cung đó nên **luôn tới được** và **không bao giờ bị dời**; thân là một
     đoạn liền trên chu trình và chỉ dài thêm khi ăn → cung trống co dần đều → **điền kín
     bàn** = **thắng**, **0 cắn thân, 0 đâm tường**.
     Năm nút toggle đổi biến thể mà **không cần chọn mode**:
     - `BFS` — **thuật toán đã kiểm chứng** (mặc định TẮT, xem mục *Nút BFS* bên dưới):
       autoplay chuyển sang động cơ `BFS tới quả + mô phỏng cả thân rắn + cổng chứng
       minh an toàn`, và trên bàn **lẻ × lẻ** thì tự chuyển sang **planner đường
       Hamilton** thay vì chu trình (`20` cột là số chẵn nên máy luôn đi nhánh chu trình).
     - `TAIL` — **safe search / bám đuôi** (mặc định TẮT, xem mục *Nút TAIL* bên dưới):
       không dùng chu trình Hamilton, chỉ có **BFS đường ngắn nhất tới quả** + luật
       **"luôn còn đường bám đuôi"**; rắn quét bàn theo kiểu **zig-zag**.
     - `SC` — **shortcut an toàn** (mặc định BẬT): cho phép bước vào ô **đuôi đang bỏ
       đi** (nước tắt ngắn nhất mà vẫn an toàn); tắt `SC` thì chỉ đi tiến trong cung.
     - `ZZ` — **longer path / zig-zag**: thay vì nhảy một bước dài trên chu trình, rắn
       **bước ngắn từng ô** (đường dài hơn, nhiều khúc hơn) — vẫn chỉ đi tiến nên không
       thể cắn thân.
     - `RND` — **chu trình ngẫu nhiên**: dựng lại bằng **divide & conquer + merge 2×2**
       (mỗi lần đều `cycleValid`) rồi chơi lại trên chu trình mới.
     Ba nút `TAIL` / `BFS` / `RND` **loại trừ nhau** (mỗi lúc chỉ một động cơ autoplay).
     Test `WIN RATE`: **20 seed × 4 biến thể × 2 cỡ bàn = 160 ván → 100% thắng, 0 chết**
     (`winTick` TB 20×12: snake 3.448, randmerge 3.930; 20×30: snake 14.236, randmerge 22.500).
     Test `AI TOGGLES ARE LIVE` chứng minh `SC` và `ZZ` **thật sự đổi nước đi** (chữ ký
     chuỗi quyết định khác nhau), không phải cờ chết.
     **Vì sao bỏ greedy**: greedy "đường ngắn nhất + bám đuôi" đo được 645 quả/12k tick
     nhưng **tự bịt túi** rồi phải cắn thân (19–38 lần/ván) — không thể chứng minh thắng.
     Spiral/lawnmower dựng động cũng đã thử: spiral là **đường hở**, không khép vòng được
     nên không điền kín bàn (chỉ đạt 10–13/240 ô).
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

- **Nút `BFS` — thuật toán đã kiểm chứng (`AutoPilot.verified`)**: bật nút này thì
  autoplay chạy đúng ý tưởng *BFS tới quả → mô phỏng cả con rắn → chỉ đi khi chứng
  minh được là an toàn*:
  1. **BFS đường ngắn nhất** từ đầu tới quả, coi thân là tường, **ô đuôi đang bỏ đi là
     ô trống**;
  2. **mô phỏng toàn bộ thân rắn** dọc hết đường đó (ăn thì dài thêm 1), sau **mỗi
     bước** kiểm tra 2 điều: **không tự cắn** và **vẫn còn đường tới đuôi**;
  3. **cổng chứng minh** (phần quyết định): chỉ nhận shortcut khi đường BFS **chính là
     dãy ô kế tiếp trên chu trình Hamilton**. Đây là điều kiện *bắt buộc*: nước rẽ tắt
     nào rời cung cũng để lại **lỗ hổng sau đầu**, thân không còn co đều và rắn bị nhốt
     rồi cắn thân. Test `PROOF GATE` đo đúng điều đó — **tắt cổng**: chết ở **24, 92, 90
     trên 240 ô** (cả 3 seed); **bật cổng**: cả 3 seed **điền kín bàn**, 0 cắn.
  4. không qua cổng → **đi tiếp trên chu trình** (nhánh thắng 100%).
  Bàn **lẻ × lẻ** (không có chu trình Hamilton) thì tự chuyển sang **planner đường
  Hamilton**: rắn đi tiến trên đường, quả đặt **đúng ô kế tiếp trên đường** nên bước nào
  cũng ăn, đuôi không bao giờ thụt → phủ kín đường = thắng.
  Test `BFS BUTTON`: **6 seed × 3 cỡ bàn (20×12, 20×20, 20×30) = 18 ván → 100% thắng,
  0 cắn, 0 tường** (`winTick` TB 20×12 = 3.526, 20×20 = 7.341, 20×30 = 14.235);
  bàn lẻ **11×13 → thắng, 143/143 ô**, 0 cắn, 0 tường. Trạng thái ghi vào `state.txt`
  (`bfs=1`, `bfsB=`).

- **Nút `TAIL` — safe search / bám đuôi (`AutoPilot.tailChase`)**: đây là thuật toán
  "quét zig-zag và luôn còn đường bám đuôi" — **không** dùng chu trình Hamilton:
  1. **BFS đường ngắn nhất** tới quả (thân là tường, **ô đuôi tính là trống**);
  2. chỉ đi bước đầu khi **mô phỏng cả đường đi** (ăn thì dài thêm) mà sau **mỗi bước**
     vẫn **không tự cắn** và **vẫn còn đường tới đuôi** — đây là bất biến
     *"luôn có đường bám đuôi"*;
  3. nếu đường tới quả không an toàn thì **chạy xa đuôi**: chọn nước an toàn có đường
     về đuôi **dài nhất** (phụ: nhiều ô trống nhất). Giữ đầu xa đuôi làm thân **trải
     rộng thành zig-zag** thay vì cuộn lại, nhờ đó mở lại đường tới quả;
  4. cùng đường → đi theo đường BFS tới đuôi; cuối cùng mới tới nước "nhiều ô trống nhất".
  **`SC` và `ZZ` không ảnh hưởng `TAIL`**: hai toggle đó là tham số của động cơ **chu
  trình** (SC = được bước vào ô đuôi đang bỏ đi, ZZ = bước ngắn từng ô). Khi `TAIL` bật,
  `activePilot()` trả về pilot safe-search và `decide()` trả lời ngay từ nhánh tail-chase,
  nên bật cả `TAIL` + `SC` + `ZZ` vẫn **chạy đúng TAIL**; `SC`/`ZZ` chỉ được **lưu lại**
  và có tác dụng trở lại khi bạn chuyển sang chu trình/`BFS`. Test `TAIL vs SC/ZZ` chứng
  minh điều đó: bật/tắt SC+ZZ cho **chữ ký chuỗi nước đi giống hệt nhau**.
  **Đo thật** (8 seed × 2 cỡ bàn, test `SAFE SEARCH / TAIL CHASE`): **0 cắn, 0 đâm
  tường**, lấp **20×12: 99,6%** (thắng 4/8), **20×30: 99,0%** (thắng 2/8). Nghĩa là nó
  **gần như** điền kín bàn và **không bao giờ chết**, nhưng **không phải lúc nào cũng
  thắng**: khi chỉ còn vài ô, điều kiện an toàn có thể không bao giờ thoả nữa và rắn
  **xoay vòng tại chỗ** (đúng như tài liệu của họ mô tả: "safe search never dies but only
  finishes 4/100 games"). Vì vậy trong app, nếu TAIL **900 tick không ăn được quả** thì
  ván **tự chơi lại** (log `stall restart tail=`) để không thấy như bị treo; muốn **điền
  kín 100%** thì dùng chu trình (mặc định) hoặc nút `BFS`.
  Tham khảo: [How the Snake AI works: BFS, tail safety & Hamiltonian cycles](https://lkforge.com/games/snake/blog-how-the-ai-works).

- **Tốc độ đều, KHÔNG gia tốc**: `interval` cố định — **60ms/ô** ở chế độ tự động,
  **110ms/ô** ở chơi tay. Nó chỉ co lại (sàn **32ms**) khi quả còn quá xa, theo ngân
  sách `3000ms / stepsToFood`, để quả tới trong vài giây. **Không còn** gia tốc dần,
  không còn hệ số thích nghi, và **không còn "teleport"** (mỗi khung mô phỏng nhiều
  bước) — chính mấy thứ đó làm hình giật và làm chế độ chậm trông như rắn trượt.
  Ở chế độ chu trình, quả chỉ sinh trong **nửa gần của cung trống** (≤160 bước) nên
  rắn không phải băng hết một vòng mới tới quả.
- **Chiến thắng**: khi không còn ô trống để sinh quả (rắn phủ kín board), game vào
  trạng thái **CHIẾN THẮNG** (thẻ vàng, điểm cuối). Watch-mode tự chơi lại sau 4s.
  Đo trên máy: rắn đạt **800/800 đốt** sau 300s, `state=4`.
- **Rắn vẽ tối giản nhưng có khối 3D**: thân đứng yên trong mỗi tick — chỉ **đầu duỗi ra** ô
  kế tiếp và **đuôi thụt vào**; mọi điểm giữa nằm đúng tâm ô nên **hình học khúc cua cố
  định, vẽ một lần là không thể gợn/phập phồng**. Đường tâm là polyline, khớp cua do
  **ROUND join** tự bo bán kính = nửa bề rộng (không bo tay) nên bề dày luôn hằng số.
  Trên cùng một đường tâm đó vẽ **5 lớp** để thành ống trụ có chiếu sáng từ trên:
  1. **bóng đổ** mềm (đen alpha thấp, lệch +1.6/+2.6dp, rộng hơn thân),
  2. **màu nền** (`#35D07F`),
  3. **mặt dưới tối** (màu trộn đen 62%, dày 0.66×thân, lệch xuống 0.19×thân),
  4. **mặt trên sáng** (trộn trắng 55%, dày 0.36×thân, lệch lên 0.18×thân),
  5. **vạch vảy** bằng `PathMeasure` (dài tối đa 90 vạch, alpha rất thấp).
  Đầu là capsule to hơn thân (0.88×cell) có gradient, **bóng đổ dưới đầu + bóng cằm +
  vệt bóng bóng (specular)**, mắt (đồng tử + ánh sáng), lỗ mũi, mắt X khi thua — **không
  vẽ lưỡi**. **Khi ăn quả, phần dài ra nằm ở ĐẦU** (`body.addFirst(cellMới)` và **không**
  `pollLast()` ở tick đó, nên ô đuôi **không đổi**: log `EAT head=.. prevHead=.. tail=..
  tailBefore=..` cho thấy `tail == tailBefore` ở mọi tick ăn) — nhờ vậy dữ liệu bám đuôi
  của AI vẫn đúng.
  **Cách vẽ đầu (chốt)**: cuối mỗi tick app **tính trước nước đi kế tiếp** (`predictNext()`,
  đã áp cả "safety net"), rồi vẽ **đầu trượt từ ô thật của nó vào ô kế tiếp** (`curCells[0]
  → ô kế`) trong khi **thân theo sau**: ô mà đầu vừa rời trở thành đốt cổ (`curCells[0]`
  vẫn được vẽ), và **đuôi thụt vào trong cùng tick** (tick sắp ăn quả thì đuôi đứng yên).
  Nhờ vậy:
  - t = 0: hình vẽ = **đúng thân thật** (đầu đúng ô, đuôi đúng ô);
  - t → 1: đầu đã **vào hẳn ô kế**, đuôi đã thụt 1 ô ⇒ **độ dài hình vẽ không đổi**, và
    t = 1 trùng khít với thân của tick sau nên **không bao giờ giật**;
  - quả: đầu **vào đúng ô quả ở cuối tick**, mà quả chỉ bị ăn ở đúng ranh giới đó ⇒ quả
    biến mất **đúng lúc đầu đặt vào ô**, không “trôi qua rồi thụt lại”;
  - nước đi dự phóng **chính là nước đi tick sau dùng** (không `decide()` lần hai) nên
    hình và logic **không thể lệch** — đã đo: **0 lần lệch** trong 25 s chạy liên tục;
  - chơi tay (waypoint/FOLLOW): **cũng dự phóng**, nhưng lấy từ **chính đường đi thật**
    (`predictManualDir()`: `findPathTo()` tới waypoint kế tiếp, hoặc hướng vừa bấm) — cùng
    hàm mà bước đi thật dùng, nên đầu vẫn **dẫn đúng ô kế** chứ không đoán mò;
  - **khi dừng** (đang đặt waypoint / xem lại route) thì `planPaused`: `hasNext = false`
    và `interpolation() = 1` ⇒ đầu đứng **đúng ô thật**, đuôi đứng **đúng ô đuôi**, hình
    **dứt điểm** (trước đây đứng nửa đường nên trông như đầu/đuôi **thụt lại 1 ô**).
  **Đầu quay mượt**: `headAngleDeg` **tiến dần** về hướng đi (`atan2(dirY, dirX)`, chọn
  đường ngắn nhất nên không bị nhảy 180°) với tốc độ ~23%/khung ở 60 fps ⇒ quay 90° mất
  **~0,3 s** thay vì **giật 1 phát 90°** như trước (dump có `ang=`/`dir=` để kiểm tra: bắt
  được cảnh đang quay, ví dụ `dir=0,-1` mà `ang=-43` rồi mới −90).
  Vị trí vẽ lấy từ `tickAccum / interval()` nên **không phụ thuộc fps**. Quả mới **hiện
  dần** (`foodFresh`, alpha `1.5t`). Đuôi bo tròn đúng bằng bề rộng thân.
- **Game loop 60 fps**: render thread tự nhịp theo **lịch tuyệt đối 16.67 ms/frame**
  (`FRAME_NS`) — không phải kiểu "vẽ xong rồi `Thread.sleep(15)`" (cách đó cho chu kỳ
  25–35 ms nên hình bị giật). Khi bị trễ hơn 4 frame thì **resync** thay vì cố đuổi.
  Vẽ bằng **`SurfaceHolder.lockHardwareCanvas()`** (GPU) khi máy ≥ API 28: thân rắn là
  5 lớp stroke + tối đa 90 vạch vảy, vẽ bằng canvas phần mềm tốn 20–35 ms/frame (chỉ
  còn 25–40 fps khi rắn dài), còn canvas GPU giữ **60 fps**; máy cũ hoặc máy không hỗ
  trợ thì **tự fallback** `lockCanvas()`. Đo trên máy (Android 15, board 20×34): trước
  khi sửa **fps 28–41**, sau khi sửa **fps 60** kể cả khi rắn dài vài trăm đốt.
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

### Unit test (không cần JUnit)

```powershell
powershell -ExecutionPolicy Bypass -File tools\test.ps1
```

`tools\test.ps1` biên dịch `app/src/test/java/**` cùng `PathPilot.java` rồi chạy
`com.dsh.snake.PathPilotTest` (25 case: hướng đi, không quay đầu, đuôi trùng ô đầu, đầu bị
bịt kín, sát tường, cứu quả) và cuối cùng **chạy lại 20.000 tick** luật `SHORT PATH` để kiểm
tra: **0 chết vì tường**, mỗi lần cắn thân chỉ xảy ra khi **không còn đường tới quả** (túi
kín), không bao giờ cắn vào cổ, và in ra bảng từng lần cắn (`tick`, `len trước → sau`,
`branch`) để soi nguyên nhân.

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
| Chạm lên board (chế độ `CHƠI`) | Đặt waypoint mới; chạm vào waypoint có sẵn để chọn nó. Rắn **tự tìm đường ngắn nhất** (BFS, thân là tường) tới waypoint đang là mục tiêu |
| 4 phím mũi tên / `✕` | Dịch waypoint đang chọn 1 ô / xoá waypoint đó |
| `AUTO` (giữa 4 mũi tên) | **Tự đặt waypoint an toàn**: lấy đường ngắn nhất đầu→quả (BFS, thân là tường), **mô phỏng ăn xong** rồi kiểm tra **vẫn còn đường bám đuôi**; chỉ khi qua mới đặt route (chỉ giữ **điểm gấp khúc** + quả). Không an toàn thì **tự chuyển sang route bám đuôi** để rắn không chết |
| `LIST` (đối xứng với `✕`) | Mở **hộp thoại danh sách waypoint**: mỗi dòng ghi số thứ tự + toạ độ ô; **kéo-thả dòng** để đổi thứ tự (thả tới đâu là chèn tới đó), chạm 1 dòng để chọn nó, chạm ra ngoài hoặc `✕` để đóng |
| `PREVIEW (n)` (dưới cụm phím) | **Playback route hiện tại như video**: rắn thật **đứng yên**, một bóng rắn **xanh cyan** bò lại đúng các waypoint **đang có** (không lưu lịch sử cũ), có nhãn `PREVIEW i / n`; nút đổi thành **`THOÁT PREVIEW`** — bấm lần nữa hoặc chạm bất kỳ đâu là thoát và **chơi tiếp** đúng chỗ đang đứng |
| `FOLLOW` (ô dưới-trái của cụm phím) | **Bám đuôi**: bấm 1 lần → *chờ chọn điểm* (chạm lên **một đốt trên thân rắn** để đặt điểm dừng); bấm lần nữa → chạy bám đuôi luôn. Rắn tự tìm đường (BFS) **chạy theo đuôi** và **luôn giữ khoảng cách ≥ 2 ô tới đuôi** (để lúc ăn quả, đuôi có dài ra thêm 1 đốt vẫn còn khoảng an toàn mà bám); dừng khi **đầu tới đúng điểm đã chọn**. Nút đổi thành **`STOP`** khi đang chạy |
| `MOVE` / `DỪNG` (thanh dưới) | Cho rắn chạy theo route / dừng lại để sửa |
| `−` / `+` (góc dưới-phải) | **Tốc độ chạy**: mỗi lần bấm đổi 1 nấc `0.5× · 0.75× · 1× · 1.5× · 2× · 3×` (giữa hai nút hiện số hiện tại). Tốc độ chia tick interval nên áp dụng cho **cả chơi tay lẫn AI**, lưu lại cho lần mở sau |
| `TAIL` / `BFS` / `SC` / `ZZ` / `RND` (hàng trên nút tốc độ) | `TAIL` = autoplay dùng **safe search / bám đuôi** (BFS tới quả, chỉ đi khi mô phỏng xong vẫn còn đường tới đuôi; không an toàn thì **chạy xa đuôi** để quét bàn theo zig-zag) — đo được **99% bàn, 0 chết** nhưng có thể xoay vòng ở cuối nên app **tự chơi lại sau 900 tick không ăn**; `BFS` = autoplay dùng **thuật toán đã kiểm chứng** (BFS đường ngắn nhất tới quả + mô phỏng cả thân rắn + **cổng chứng minh** giữ nguyên cung Hamilton; bàn lẻ × lẻ tự chuyển sang **planner đường Hamilton**) — bật/tắt sẽ chơi lại ván mới bằng động cơ đó; `SC` = **shortcut an toàn** (được bước vào ô đuôi đang bỏ đi); `ZZ` = **zig-zag / longer path** (bước ngắn từng ô thay vì nhảy dài, đường dài hơn — ý tưởng từ [chuyangliu/snake](https://github.com/chuyangliu/snake)); `RND` = autoplay dùng **chu trình Hamilton ngẫu nhiên** (divide-and-merge các dải, có validate) — bật/tắt sẽ chơi lại ngay trên chu trình mới. Ba nút `TAIL` / `BFS` / `RND` **loại trừ nhau**. **Toggle đang bật được áp dụng ngay lập tức** (TAIL/BFS/SC/ZZ đổi hành vi từ tick kế tiếp, RND đổi chu trình), và trạng thái được ghi vào `state.txt` (`tail=`, `bfs=`, `sc=`, `zz=`). Test `AI TOGGLES ARE LIVE` chứng minh `SC`/`ZZ` **thật sự đổi nước đi**; test `AI TOGGLES` + `WIN RATE` xác nhận mọi biến thể vẫn điền kín bàn, 0 cắn, 0 chết tường; test `SAFE SEARCH / TAIL CHASE` đo `TAIL` đạt 99% bàn với 0 chết |
| Menu tạm dừng | `TIẾP TỤC` để chạy tiếp · **`AUTOPLAY`** = bật lại AI cho ván đang chơi (giữ điểm, kể cả khi bạn vừa chạm badge để điều khiển tay) · **`CHƠI LẠI`** = **chơi lại ván mới ngay tại chỗ** với đúng mode + đúng thuật toán (SC/ZZ/RND) đang chọn |
| Vuốt 4 hướng (chế độ AI) | Chuyển sang tự điều khiển (rắn đứng chờ waypoint) |
| Chạm `CHƠI` / `AUTOPLAY` | Bắt đầu chơi tay (waypoint) hoặc để AI autoplay (2 nút xếp dọc) |
| Chạm `❚❚` (góc dưới phải) | Tạm dừng / tiếp tục |
| Chạm khi thua | Chơi lại; menu có `CHƠI LẠI` · `AUTOPLAY` |

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
