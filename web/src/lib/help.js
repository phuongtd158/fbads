// Nội dung trang Hướng dẫn. Định dạng chữ trong văn bản: **đậm**, `mã`.
// Kiểu khối: p | steps | tips | note | table | example | faq

export const TOPICS = [
  {
    id: 'start', icon: 'Rocket', title: 'Bắt đầu nhanh', summary: '5 bước từ dùng thử đến chạy thật',
    blocks: [
      { t: 'p', text: 'FB Ads Auto giúp bạn **bật/tắt camp và chỉnh ngân sách Facebook Ads tự động**, thay vì phải dậy sớm làm tay mỗi ngày. Nên đi theo thứ tự dưới đây để an toàn:' },
      { t: 'steps', items: [
        '**Làm quen ở chế độ Dùng thử.** Tool mặc định dùng dữ liệu giả, bạn thoải mái bật/tắt camp, tạo lịch, tạo rule mà không ảnh hưởng quảng cáo thật.',
        '**Kết nối Facebook** tại Cài đặt → Kết nối Facebook. Làm theo trình hướng dẫn 3 bước (lấy token, kiểm tra, chọn tài khoản). Tool tự chuyển sang chế độ **Chạy thử**.',
        '**Tạo lịch tự động** cho việc bạn làm mỗi sáng, ví dụ bật camp lúc 6:00 và tắt lúc 23:00.',
        '**Tạo rule bảo vệ ngân sách**, ví dụ tắt camp khi CPA quá cao, giảm ngân sách khi ROAS thấp.',
        '**Để Chạy thử 2–3 ngày**, xem trong Nhật ký thấy tool làm đúng ý rồi mới chuyển sang **Chạy thật**.',
      ] },
      { t: 'note', tone: 'info', text: 'Tool chỉ chạy khi **máy tính đang bật** và cửa sổ chạy tool (`start.bat`) đang mở. Máy tắt hoặc ngủ thì lịch không chạy.' },
    ],
  },
  {
    id: 'overview', icon: 'LayoutDashboard', title: 'Tổng quan', summary: 'Đọc số liệu, bật/tắt camp, sửa ngân sách',
    blocks: [
      { t: 'p', text: 'Trang Tổng quan cho bạn thấy tình hình quảng cáo **hôm nay** và cho phép điều khiển ngay tại chỗ.' },
      { t: 'table', head: ['Ô số liệu', 'Ý nghĩa'], rows: [
        ['Chi tiêu hôm nay', 'Tổng tiền đã tiêu hôm nay của tất cả camp. Vòng tròn cho biết đã dùng bao nhiêu % tổng ngân sách ngày của các camp đang chạy.'],
        ['Đang chạy', 'Số camp đang chạy trên tổng số camp.'],
        ['Kết quả', 'Số kết quả hôm nay theo loại bạn chọn ở Cài đặt → Chung (đơn hàng, khách để lại thông tin, tin nhắn…).'],
        ['CPA trung bình', 'Chi phí trung bình cho mỗi kết quả = tổng chi tiêu chia tổng kết quả. Càng thấp càng tốt.'],
        ['ROAS trung bình', 'Doanh thu chia chi tiêu. Trên 1 là có lãi trên quảng cáo, càng cao càng tốt.'],
      ] },
      { t: 'steps', title: 'Bạn có thể làm gì', items: [
        '**Bật/tắt một camp:** gạt công tắc ở đầu dòng. Thay đổi có hiệu lực ngay trên Facebook.',
        '**Sửa ngân sách:** bấm vào số ngân sách, gõ số mới rồi nhấn Enter (Esc để huỷ).',
        '**Tìm và lọc:** gõ vào ô tìm kiếm (phím tắt `/`) hoặc lọc Tất cả / Đang chạy / Tạm dừng. Nếu tài khoản có nhóm quảng cáo, chuyển qua lại bằng nút Chiến dịch / Nhóm QC.',
        '**Bật/tắt hàng loạt:** nút Bật tất cả / Tắt tất cả áp dụng cho các dòng **đang hiển thị** sau khi lọc, và luôn hỏi xác nhận trước.',
        '**Làm mới:** số liệu tự cập nhật mỗi 60 giây, hoặc bấm nút Làm mới.',
      ] },
      { t: 'table', head: ['Màu ROAS', 'Nghĩa là'], rows: [['Xanh', 'ROAS từ 2 trở lên — tốt'], ['Vàng', 'ROAS từ 1 đến dưới 2 — tạm ổn, cần theo dõi'], ['Đỏ', 'ROAS dưới 1 — đang lỗ trên quảng cáo']] },
      { t: 'note', tone: 'warning', text: 'Ô ngân sách hiện dấu **–** nghĩa là camp đó dùng **ngân sách cấp chiến dịch (CBO)** hoặc ngân sách đặt ở cấp khác. Hãy chỉnh ngân sách ở đúng cấp có số tiền hiển thị.' },
      { t: 'note', tone: 'info', text: 'Số liệu là của **hôm nay** theo múi giờ tài khoản quảng cáo, có thể trễ vài phút so với Ads Manager.' },
    ],
  },
  {
    id: 'schedules', icon: 'CalendarClock', title: 'Lịch tự động', summary: 'Hẹn giờ bật/tắt camp và đổi ngân sách',
    blocks: [
      { t: 'p', text: 'Lịch giúp tool làm thay bạn những việc lặp lại theo giờ, không cần dậy sớm bấm tay.' },
      { t: 'steps', title: 'Tạo lịch', items: [
        'Vào **Lịch tự động** → bấm **Thêm lịch**, hoặc chọn một mẫu ở dòng “Tạo nhanh”.',
        'Chọn **hành động**: Bật camp, Tắt camp, hoặc Đổi ngân sách.',
        'Đặt **giờ chạy** và **các ngày** trong tuần (có nút chọn nhanh Hằng ngày, T2–T6, Cuối tuần).',
        'Chọn **các chiến dịch** áp dụng rồi bấm **Lưu lịch**.',
      ] },
      { t: 'table', head: ['Kiểu đổi ngân sách', 'Ví dụ'], rows: [['Theo %', 'Nhập `30` để tăng 30%, nhập `-50` để giảm một nửa.'], ['Số tiền cố định', 'Nhập `500000` để đặt ngân sách ngày đúng 500.000.']] },
      { t: 'example', title: 'Ví dụ thường dùng', items: ['**06:00 hằng ngày** — Bật camp buổi sáng.', '**23:00 hằng ngày** — Tắt camp buổi tối để không tốn tiền ban đêm.', '**Thứ 7, Chủ nhật 07:00** — Tăng ngân sách 30% cho dịp cuối tuần.'] },
      { t: 'tips', items: [
        'Dòng thời gian ở đầu trang cho thấy các lịch sẽ chạy **hôm nay** và giờ hiện tại.',
        'Muốn tạm ngưng một lịch mà không xoá: gạt công tắc ở góc thẻ.',
        'Nút **Chạy ngay** để thử lịch tức thì. Nếu đang ở chế độ Chạy thử, tool chỉ ghi Nhật ký chứ không thay đổi camp.',
        'Camp đã đúng trạng thái (ví dụ đã bật rồi) thì tool bỏ qua, không báo lỗi.',
      ] },
      { t: 'note', tone: 'info', text: 'Nếu máy bật trễ tối đa **10 phút** so với giờ hẹn, tool vẫn chạy bù. Trễ hơn thì lịch đó bị bỏ qua đến lần sau. Giờ chạy tính theo múi giờ ở Cài đặt → Chung.' },
    ],
  },
  {
    id: 'rules', icon: 'Zap', title: 'Rule hiệu quả', summary: 'Tự tắt camp lỗ, tăng ngân sách camp tốt',
    blocks: [
      { t: 'p', text: 'Rule cho tool tự quyết định dựa trên **số liệu của khoảng thời gian bạn chọn** (hôm nay, hôm qua, 3 hoặc 7 ngày gần nhất) của từng camp. Cứ mỗi vài phút (mặc định 15 phút, đổi được ở Cài đặt → Chung), tool kiểm tra từng camp đang chạy và làm theo rule nếu điều kiện đúng.' },
      { t: 'table', head: ['Ô trong form', 'Ý nghĩa'], rows: [
        ['Khoảng thời gian', 'Số liệu tính trong khoảng nào. Chuyển đổi thường về trễ nên khi **tắt hoặc giảm** camp nên dùng “3 ngày”, tránh chỉ dựa vào hôm nay.'],
        ['Nếu (số liệu)', 'Chọn CPA, ROAS, Chi tiêu hoặc Số kết quả, rồi “lớn hơn” hoặc “nhỏ hơn” một giá trị.'],
        ['Chi tiêu tối thiểu', 'Chỉ xét camp đã tiêu ít nhất số tiền này. **Rất quan trọng** để không tắt nhầm camp mới chạy, chưa đủ dữ liệu.'],
        ['Thì', 'Tắt camp, Tăng/Giảm ngân sách theo %, hoặc **Chỉ thông báo** (không đổi camp, chỉ ghi Nhật ký và gửi Telegram).'],
        ['Trần / Sàn ngân sách', 'Giới hạn để ngân sách không tăng quá cao hoặc giảm quá thấp sau nhiều lần áp dụng.'],
        ['Không lặp lại trong (giờ)', 'Sau khi rule đã áp dụng cho một camp, chờ số giờ này mới áp dụng lại cho chính camp đó. Tránh tăng/giảm liên tục.'],
        ['Khung giờ', 'Chỉ chạy rule trong khoảng giờ này (để trống là chạy cả ngày).'],
        ['Áp dụng cho', 'Tất cả camp đang chạy, hoặc chọn từng camp cụ thể.'],
      ] },
      { t: 'example', title: 'Ví dụ rule hay dùng', items: [
        '**Tắt camp CPA cao:** nếu CPA trong 3 ngày gần nhất lớn hơn 150.000 và đã chi ≥ 300.000 thì tắt camp.',
        '**Tắt camp không ra kết quả:** nếu số kết quả trong 3 ngày nhỏ hơn 1 và đã chi ≥ 500.000 thì tắt camp.',
        '**Tăng khi ROAS tốt:** nếu ROAS lớn hơn 3 và đã chi ≥ 200.000 thì tăng 20% ngân sách, trần 2.000.000, nghỉ 24 giờ.',
        '**Giảm khi ROAS thấp:** nếu ROAS nhỏ hơn 1,5 thì giảm 20% ngân sách, nghỉ 24 giờ.',
      ] },
      { t: 'tips', items: [
        'Luôn đặt **chi tiêu tối thiểu**. Không có nó, camp vừa chạy vài phút chưa ra kết quả sẽ bị tắt oan.',
        'Khi dùng rule tăng, hãy đặt **trần ngân sách** và **thời gian nghỉ** để tránh ngân sách phình to ngoài ý muốn.',
        'Thay đổi ngân sách quá lớn có thể khiến Facebook học lại từ đầu. Nên tăng/giảm vừa phải (khoảng 20% mỗi lần).',
        'Bấm **Xem trước** (trong form hoặc trên thẻ rule) để biết rule **đang khớp camp nào ngay bây giờ** mà chưa thay đổi gì.',
        'Muốn thử một rule mới an toàn: chọn hành động **Chỉ thông báo** vài ngày, thấy đúng ý rồi mới đổi sang tắt/đổi ngân sách.',
        'Bấm **Kiểm tra ngay** để tool chạy toàn bộ rule tức thì, rồi xem Nhật ký.',
      ] },
      { t: 'note', tone: 'warning', text: 'Rule chỉ xét camp **đang chạy**. Camp bị rule tắt sẽ không tự bật lại — bạn cần bật lại thủ công hoặc bằng lịch.' },
    ],
  },
  {
    id: 'logs', icon: 'ScrollText', title: 'Nhật ký & xem lỗi', summary: 'Tool đã làm gì, và vì sao thất bại',
    blocks: [
      { t: 'p', text: 'Nhật ký ghi lại **mọi việc tool đã làm**: lịch, rule, thao tác thủ công của bạn và lỗi hệ thống. Tool giữ 1000 dòng gần nhất.' },
      { t: 'table', head: ['Nhãn', 'Ý nghĩa'], rows: [['Thành công', 'Đã thay đổi camp thật trên Facebook.'], ['Chạy thử', 'Tool *sẽ* làm việc này, nhưng chưa thực hiện vì đang ở chế độ Chạy thử.'], ['Bỏ qua', 'Tool cố ý không làm vì đang bảo vệ ngân sách (camp đang học, đã chạm giới hạn thay đổi mỗi ngày). Chỉ là ghi nhận.'], ['Cảnh báo', 'Rule “Chỉ thông báo” đã khớp. Tool không đổi camp.'], ['Lỗi', 'Thao tác thất bại. Dòng có vạch đỏ và mã lỗi (ví dụ Facebook #190).']] },
      { t: 'steps', title: 'Xem chi tiết một dòng', items: [
        'Bấm vào dòng nhật ký (hoặc nút **Chi tiết** / **Xem lỗi** bên phải).',
        'Hộp chi tiết cho biết **thời gian, nguồn** (lịch/rule nào, có nút Mở), **đối tượng** và **hành động**.',
        'Nếu lỗi: mục **Nguyên nhân** giải thích bằng tiếng Việt, mục **Cách khắc phục** gợi ý việc cần làm kèm nút đi thẳng tới nơi sửa.',
        'Thành công/Chạy thử: xem bảng **Trước → Sau** (trạng thái, ngân sách), **điều kiện rule đã khớp** và số liệu của camp lúc đó.',
        'Mở **Chi tiết kỹ thuật** để xem mã lỗi Facebook, mã phụ, loại lỗi, `fbtrace_id` và yêu cầu đã gửi.',
      ] },
      { t: 'table', head: ['Mã lỗi thường gặp', 'Nghĩa là', 'Cách xử lý'], rows: [
        ['190', 'Token hết hạn hoặc bị thu hồi', 'Tạo token mới ở Cài đặt → Kết nối Facebook.'],
        ['10 / 200', 'Token thiếu quyền hoặc chưa được gán tài khoản quảng cáo', 'Tick `ads_management`, `ads_read` và gán tài khoản cho token.'],
        ['4 / 17 / 32 / 613', 'Gọi Facebook quá nhiều lần', 'Chờ 5–10 phút; tăng chu kỳ kiểm tra rule ở Cài đặt → Chung.'],
        ['100', 'Facebook từ chối tham số (ID sai, camp lưu trữ…)', 'Làm mới danh sách camp, chọn lại camp trong lịch/rule.'],
        ['Lỗi mạng', 'Máy không gọi được Facebook', 'Kiểm tra internet/VPN, tắt chế độ ngủ của máy.'],
      ] },
      { t: 'tips', items: [
        'Nút **Chạy lại lịch** / **Kiểm tra lại rule** ở cuối hộp chi tiết giúp thử lại ngay sau khi bạn đã sửa lỗi.',
        'Nút **Hoàn tác** đưa camp về trạng thái/ngân sách trước đó (trong 3 ngày). Nếu camp đã bị đổi tiếp, tool hỏi lại trước khi ghi đè. Việc do rule làm sẽ được rule tạm hoãn 24 giờ để không làm lại ngay.',
        'Nút **Sao chép chi tiết** gom toàn bộ thông tin (dạng văn bản) để gửi cho người hỗ trợ.',
        'Lọc theo **trạng thái** (Thành công/Chạy thử/Lỗi) và **nguồn** (Lịch/Rule/Thủ công/Hệ thống), hoặc gõ mã lỗi vào ô tìm kiếm.',
        'Access Token **không bao giờ** được ghi vào nhật ký.',
      ] },
      { t: 'note', tone: 'info', text: 'Các dòng nhật ký ghi trước khi có tính năng này chỉ có thông tin cơ bản (không có Trước → Sau hay mã lỗi).' },
    ],
  },
  {
    id: 'protect', icon: 'ShieldAlert', title: 'Bảo vệ ngân sách', summary: 'Phanh an toàn: camp đang học, giới hạn ngày, dừng khẩn',
    blocks: [
      { t: 'p', text: 'Ba “phanh an toàn” giúp rule và lịch không làm ngân sách đi quá xa, kể cả khi bạn không online. Cài ở **Cài đặt → Bảo vệ ngân sách**.' },
      { t: 'table', head: ['Tính năng', 'Làm gì', 'Áp dụng cho'], rows: [
        ['Bỏ qua camp đang học', 'Không để **rule** tăng/giảm ngân sách camp đang trong giai đoạn học, vì Facebook sẽ học lại từ đầu.', 'Rule đổi ngân sách'],
        ['Giới hạn thay đổi mỗi ngày', 'Tổng % ngân sách một camp được đổi trong ngày, tính trên ngân sách đầu ngày. Ví dụ 30%: gốc 500.000 thì tối đa lên 650.000 hoặc xuống 350.000 dù rule chạy nhiều lần.', 'Rule đổi ngân sách (lịch do bạn đặt không bị giới hạn)'],
        ['Dừng khẩn', 'Khi tổng chi tiêu hôm nay của mọi camp đạt mức bạn đặt, tự **tắt tất cả camp đang chạy** và báo Telegram. Mỗi ngày tối đa một lần.', 'Toàn bộ tài khoản'],
      ] },
      { t: 'tips', items: [
        'Camp đang học có nhãn **Đang học** ở trang Tổng quan.',
        'Khi tool bỏ qua vì các lý do trên, Nhật ký có dòng **Bỏ qua** ghi rõ nguyên nhân (mỗi lý do chỉ ghi một lần mỗi ngày cho mỗi camp).',
        'Dừng khẩn được kiểm tra theo chu kỳ rule (mặc định 15 phút), nên có thể chi vượt mức một chút trước khi tắt.',
      ] },
      { t: 'note', tone: 'warning', text: 'Dừng khẩn chỉ **tắt**, không tự bật lại. Hôm sau hãy dùng lịch “Bật camp buổi sáng” để chạy lại. Ở chế độ Chạy thử, dừng khẩn chỉ ghi Nhật ký.' },
    ],
  },
  {
    id: 'connect', icon: 'KeyRound', title: 'Kết nối Facebook', summary: 'Lấy token, chọn tài khoản quảng cáo',
    blocks: [
      { t: 'p', text: 'Để điều khiển quảng cáo thật, tool cần **Access Token** (chìa khoá do Facebook cấp) và biết **tài khoản quảng cáo** nào cần quản lý. Vào **Cài đặt → Kết nối Facebook → Kết nối Facebook** và làm theo trình hướng dẫn 3 bước.' },
      { t: 'table', head: ['Cách lấy token', 'Đặc điểm'], rows: [
        ['Đăng nhập Facebook', 'Dễ nhất khi dùng lâu dài: khai báo 1 lần App ID, App Secret và địa chỉ chuyển hướng trong ứng dụng Meta, sau đó chỉ cần bấm **Đăng nhập bằng Facebook**. Token tự gia hạn lên **60 ngày**, hết hạn thì bấm lại.'],
        ['Dán token (Graph API Explorer)', 'Mất vài phút. Token ban đầu chỉ sống vài giờ, tool giúp **gia hạn lên 60 ngày** nếu bạn nhập App ID và App Secret (chỉ dùng 1 lần, không lưu).'],
        ['Cách ổn định (Người dùng hệ thống)', 'Cần Business Manager. Token **không hết hạn**, phù hợp chạy lâu dài.'],
      ] },
      { t: 'steps', title: 'Quyền cần có', items: ['`ads_management` — để bật/tắt camp và đổi ngân sách.', '`ads_read` — để đọc chiến dịch và số liệu.', 'Tài khoản quảng cáo phải được **gán** cho người dùng/token đó (quyền quản lý chiến dịch).'] },
      { t: 'tips', items: [
        'Thẻ trạng thái ở đầu trang Cài đặt cho biết đã kết nối đúng chưa, token còn bao nhiêu ngày và có đủ quyền không. Bấm **Kiểm tra lại** bất cứ lúc nào.',
        'Token sắp hết hạn (còn dưới 7 ngày) sẽ có thanh cảnh báo màu vàng ở đầu mọi trang.',
        'Không thấy tài khoản quảng cáo nào trong danh sách? Dùng mục “Nhập ID tài khoản thủ công” (ID nằm cạnh tên tài khoản trong Ads Manager).',
      ] },
      { t: 'note', tone: 'warning', text: 'Token giống như mật khẩu. **Đừng gửi cho ai** và đừng đưa file `data.json` lên mạng.' },
    ],
  },
  {
    id: 'modes', icon: 'ShieldCheck', title: 'Chế độ hoạt động', summary: 'Dùng thử, Chạy thử và Chạy thật',
    blocks: [
      { t: 'table', head: ['Chế độ', 'Dùng Facebook thật?', 'Lịch/rule có thay đổi camp thật?'], rows: [
        ['Dùng thử', 'Không (dữ liệu giả)', 'Không'],
        ['Chạy thử', 'Có', 'Không — chỉ ghi vào Nhật ký với nhãn “Chạy thử”'],
        ['Chạy thật', 'Có', 'Có — bật/tắt camp và đổi ngân sách thật'],
      ] },
      { t: 'p', text: '**Chạy thử** là chế độ an toàn để kiểm tra: bạn xem trong Nhật ký tool *sẽ* làm gì mà chưa động vào quảng cáo. Khi thấy hợp lý sau vài ngày, hãy chuyển sang Chạy thật tại Cài đặt → Chế độ hoạt động.' },
      { t: 'note', tone: 'info', text: 'Các thao tác bạn làm **thủ công** (gạt công tắc, sửa ngân sách ở trang Tổng quan) luôn có hiệu lực thật khi đã kết nối Facebook, kể cả ở chế độ Chạy thử.' },
    ],
  },
  {
    id: 'telegram', icon: 'Send', title: 'Thông báo Telegram', summary: 'Nhận tin mỗi khi tool thay đổi camp',
    blocks: [
      { t: 'p', text: 'Khi bật, tool gửi cho bạn tin nhắn Telegram mỗi lần lịch/rule thay đổi camp (kể cả lỗi), cộng thêm **báo cáo tổng hợp mỗi sáng**. Tính năng này không bắt buộc.' },
      { t: 'steps', title: 'Thiết lập', items: [
        'Trên Telegram, chat với **@BotFather**, gõ `/newbot` và làm theo để nhận **Bot Token**.',
        'Nhắn một tin bất kỳ cho bot vừa tạo.',
        'Mở `https://api.telegram.org/bot<TOKEN>/getUpdates` (thay `<TOKEN>` bằng token của bạn) và lấy số `chat.id` — đó là **Chat ID**.',
        'Dán cả hai vào Cài đặt → Telegram, chọn giờ nhận báo cáo, bấm **Lưu** rồi **Gửi tin thử**.',
      ] },
    ],
  },
  {
    id: 'security', icon: 'Lock', title: 'Bảo mật & chia sẻ', summary: 'Mật khẩu đăng nhập, dữ liệu, cho người khác dùng',
    blocks: [
      { t: 'steps', items: [
        '**Đặt mật khẩu** tại Cài đặt → Bảo mật (ít nhất 8 ký tự). Từ đó mọi người phải đăng nhập mới dùng được.',
        'Nhập sai 5 lần, địa chỉ đó bị khoá 15 phút.',
        'Phiên đăng nhập giữ 30 ngày. Bấm **Đăng xuất** ở thanh bên hoặc trong Cài đặt để thoát. Đổi mật khẩu sẽ đăng xuất mọi thiết bị khác.',
      ] },
      { t: 'tips', items: [
        'Toàn bộ dữ liệu (token, lịch, rule, nhật ký) nằm trong file `data.json` cạnh tool. **Sao lưu file này** và không chia sẻ nó.',
        'Mặc định tool chỉ mở trên máy bạn (`127.0.0.1`). Muốn người khác dùng, cần mở ra mạng kèm mật khẩu (biến môi trường `HOST=0.0.0.0` và `APP_PASSWORD`).',
        'Quên mật khẩu? Tắt tool, mở `data.json`, đặt `"passwordHash": ""` rồi chạy lại.',
      ] },
    ],
  },
  {
    id: 'shortcuts', icon: 'Keyboard', title: 'Phím tắt', summary: 'Thao tác nhanh bằng bàn phím',
    blocks: [
      { t: 'table', head: ['Phím', 'Tác dụng'], rows: [
        ['`Ctrl` + `K` (hoặc `⌘` + `K`)', 'Mở thanh tìm nhanh: chuyển trang, làm mới, kiểm tra rule, đổi giao diện, tìm chiến dịch.'],
        ['`/`', 'Đưa con trỏ vào ô tìm kiếm chiến dịch (trang Tổng quan).'],
        ['`↑` `↓` rồi `Enter`', 'Chọn và mở mục trong thanh tìm nhanh.'],
        ['`Esc`', 'Đóng hộp thoại, thanh tìm nhanh, hoặc huỷ sửa ngân sách.'],
        ['`Enter`', 'Lưu ngân sách đang sửa; xác nhận hộp thoại hỏi.'],
      ] },
      { t: 'p', text: 'Đổi giao diện sáng/tối bằng nút ở góc trên bên phải, hoặc chọn màu nhấn tại Cài đặt → Giao diện.' },
    ],
  },
  {
    id: 'glossary', icon: 'BookText', title: 'Thuật ngữ', summary: 'CPA, ROAS, CBO… là gì?',
    blocks: [
      { t: 'glossary', items: [
        ['Chiến dịch (Camp)', 'Cấp cao nhất trong quảng cáo Facebook, chứa các nhóm quảng cáo. Thường là đơn vị bạn bật/tắt.'],
        ['Nhóm quảng cáo (Nhóm QC)', 'Cấp giữa: quyết định đối tượng, ngân sách và lịch chạy của các quảng cáo bên trong.'],
        ['Chi tiêu', 'Số tiền quảng cáo đã tiêu.'],
        ['Kết quả', 'Hành động bạn muốn khách làm (mua hàng, để lại thông tin, nhắn tin…). Loại kết quả chọn ở Cài đặt → Chung.'],
        ['CPA', 'Chi phí trên mỗi kết quả = Chi tiêu ÷ Số kết quả.'],
        ['ROAS', 'Doanh thu trên chi tiêu quảng cáo = Doanh thu ÷ Chi tiêu. ROAS 3 nghĩa là 1 đồng quảng cáo mang về 3 đồng doanh thu.'],
        ['Ngân sách ngày', 'Số tiền tối đa Facebook được tiêu cho camp/nhóm trong một ngày.'],
        ['CBO', 'Ngân sách chiến dịch: đặt ngân sách ở cấp camp và Facebook tự chia cho các nhóm. Khi đó nhóm QC không có ngân sách riêng.'],
        ['Access Token', 'Chuỗi ký tự Facebook cấp để tool được phép điều khiển quảng cáo thay bạn.'],
        ['Chạy thử (Dry run)', 'Chế độ tool chỉ ghi lại việc *sẽ* làm mà không thực hiện thật.'],
      ] },
    ],
  },
  {
    id: 'faq', icon: 'LifeBuoy', title: 'Câu hỏi thường gặp', summary: 'Xử lý sự cố phổ biến',
    blocks: [
      { t: 'faq', items: [
        ['Vì sao tool không cho tôi lưu lịch hoặc rule?', 'Tool chặn các thiết lập dễ gây thiệt hại hoặc vô nghĩa, và báo lỗi ngay dưới ô cần sửa. Các luật chính: **Lịch** — phải có giờ hợp lệ, ít nhất 1 ngày và 1 camp; không giảm ngân sách từ 100% trở lên; không đổi ngân sách cho camp dùng CBO; không có hai lịch bật/tắt ngược nhau cùng giờ cho cùng camp. **Rule** — phải có chi tiêu tối thiểu (với CPA, ROAS, số kết quả); rule đổi ngân sách phải nghỉ ít nhất 1 giờ giữa hai lần, tăng tối đa 100% và giảm tối đa 90% mỗi lần; trần phải lớn hơn hoặc bằng sàn; khung giờ không được qua đêm. **Sửa ngân sách tay** — phải lớn hơn 0, và hỏi xác nhận nếu tăng gấp đôi trở lên hoặc giảm một nửa trở lên (tránh gõ nhầm số 0).'],
        ['Vì sao rule không tăng/giảm ngân sách camp của tôi?', 'Rule chỉ đổi ngân sách khi mọi điều kiện đều đạt. Hãy mở **Xem trước** trên thẻ rule để thấy lý do từng camp: chưa đủ chi tiêu tối thiểu, đang trong thời gian nghỉ, ngoài khung giờ, **camp đang học** (Cài đặt → Bảo vệ ngân sách), đã **chạm giới hạn thay đổi mỗi ngày**, hoặc camp dùng CBO nên không có ngân sách riêng. Các dòng “Bỏ qua” trong Nhật ký cũng ghi rõ lý do.'],
        ['Tool tắt nhầm camp, làm sao đưa về như cũ?', 'Vào **Nhật ký**, mở dòng tắt camp đó rồi bấm **Hoàn tác** (trong vòng 3 ngày). Sau đó nên sửa rule (tăng chi tiêu tối thiểu hoặc dùng khoảng “3 ngày”) và dùng **Xem trước** trước khi bật lại.'],
        ['Tool báo “Mất kết nối Facebook” thì sao?', 'Vào Cài đặt → Kết nối Facebook xem thẻ trạng thái, nó nêu rõ lý do. Thường gặp: token hết hạn (tạo token mới), thiếu quyền `ads_management`/`ads_read`, hoặc máy không có mạng. Sau khi sửa, bấm **Kiểm tra lại**.'],
        ['Lịch không chạy đúng giờ?', 'Kiểm tra lần lượt: (1) máy có đang bật và cửa sổ `start.bat` còn mở không; (2) lịch có đang được bật (công tắc) và đúng ngày không; (3) múi giờ ở Cài đặt → Chung đã đúng chưa; (4) chế độ có đang là Chạy thử không — khi đó lịch chỉ ghi Nhật ký; (5) xem Nhật ký có báo lỗi không.'],
        ['Không đổi được ngân sách?', 'Camp có thể dùng ngân sách chiến dịch (CBO) nên nhóm quảng cáo không có ngân sách riêng. Hãy chỉnh ngân sách ở cấp camp. Ô hiện dấu “–” nghĩa là cấp đó không có ngân sách.'],
        ['Số liệu khác với Ads Manager?', 'Tool lấy số liệu **hôm nay** theo múi giờ tài khoản và có thể trễ vài phút. “Kết quả” tính theo loại bạn chọn ở Cài đặt → Chung, có thể khác cột bạn đang xem trong Ads Manager.'],
        ['Rule tắt nhầm camp mới chạy?', 'Hãy tăng **chi tiêu tối thiểu** của rule để camp có đủ dữ liệu trước khi bị xét. Xem Nhật ký để biết rule nào đã tắt, rồi bật lại camp thủ công.'],
        ['Token hết hạn thì sao?', 'Sẽ có thanh cảnh báo khi còn dưới 7 ngày. Vào Cài đặt → Kết nối Facebook → Đổi token để dán token mới. Dùng cách “Người dùng hệ thống” để có token không hết hạn.'],
        ['Quên mật khẩu đăng nhập?', 'Tắt tool, mở file `data.json`, đặt `"passwordHash": ""`, lưu và chạy lại. Sau đó vào Cài đặt → Bảo mật đặt mật khẩu mới.'],
        ['Dữ liệu của tôi lưu ở đâu?', 'Trong file `data.json` cạnh tool, trên máy bạn. Tool chỉ gửi dữ liệu tới Facebook (và Telegram nếu bạn bật).'],
      ] },
    ],
  },
]

export const topicById = (id) => TOPICS.find((t) => t.id === id) || TOPICS[0]

// Văn bản thuần của một chủ đề — dùng cho tìm kiếm
export function plainText(topic) {
  const out = [topic.title, topic.summary]
  for (const b of topic.blocks) {
    if (b.text) out.push(b.text)
    if (b.title) out.push(b.title)
    if (b.items) for (const i of b.items) out.push(Array.isArray(i) ? i.join(' ') : i)
    if (b.rows) for (const r of b.rows) out.push(r.join(' '))
    if (b.head) out.push(b.head.join(' '))
  }
  return out.join(' ').replace(/[*`]/g, '')
}

// Gợi ý ngắn khi rê chuột vào dấu ? (key → { text, topic })
export const TIPS = {
  cpa: { text: 'CPA = chi phí cho mỗi kết quả (chi tiêu ÷ số kết quả). Càng thấp càng tốt.', topic: 'glossary' },
  roas: { text: 'ROAS = doanh thu ÷ chi tiêu. Trên 1 là có lãi trên quảng cáo; từ 2 trở lên là tốt.', topic: 'glossary' },
  results: { text: 'Số hành động khách đã làm (mua hàng, để lại thông tin, nhắn tin…). Chọn loại ở Cài đặt → Chung.', topic: 'glossary' },
  budget: { text: 'Ô hiện “–” nghĩa là camp dùng ngân sách cấp chiến dịch (CBO), hãy chỉnh ở cấp có số tiền.', topic: 'overview' },
  scheduleTime: { text: 'Một lịch có thể có nhiều giờ chạy trong ngày; giờ nào hôm nay chưa tới thì chạy luôn hôm nay. Nếu máy bật trễ tối đa 10 phút so với giờ hẹn, tool vẫn chạy bù. Giờ tính theo múi giờ ở Cài đặt → Chung.', topic: 'schedules' },
  scheduleBudget: { text: 'Theo %: nhập số âm để giảm (vd -30). Số tiền cố định: đặt ngân sách ngày đúng bằng số đó.', topic: 'schedules' },
  minSpend: { text: 'Chỉ xét camp đã tiêu ít nhất số tiền này, để không tắt nhầm camp mới chạy chưa đủ dữ liệu.', topic: 'rules' },
  ruleAction: { text: 'Thay đổi ngân sách quá lớn có thể khiến Facebook học lại từ đầu. Nên tăng/giảm khoảng 20% mỗi lần.', topic: 'rules' },
  cap: { text: 'Trần/sàn là mức ngân sách tối đa/tối thiểu, giữ cho ngân sách không phình to hay xuống quá thấp sau nhiều lần áp dụng.', topic: 'rules' },
  cooldown: { text: 'Sau khi rule đã áp dụng cho một camp, chờ số giờ này mới áp dụng lại cho chính camp đó.', topic: 'rules' },
  window: { text: 'Chỉ chạy rule trong khoảng giờ này. Để trống là chạy cả ngày.', topic: 'rules' },
  modes: { text: 'Chạy thử: dùng Facebook thật nhưng chỉ ghi Nhật ký, không đổi camp. Chạy thật: thay đổi camp thật.', topic: 'modes' },
  token: { text: 'Access Token là chìa khoá Facebook cấp để tool điều khiển quảng cáo. Đừng chia sẻ cho ai.', topic: 'connect' },
  resultAction: { text: 'Loại “kết quả” dùng để tính CPA/ROAS. Chọn đúng mục tiêu bạn chạy quảng cáo.', topic: 'glossary' },
  range: { text: 'Chọn số liệu của khoảng nào để so với ngưỡng. Chuyển đổi thường về trễ nên khi tắt/giảm camp nên dùng “3 ngày”, tránh chỉ dựa vào hôm nay.', topic: 'rules' },
  notify: { text: 'Rule chỉ thông báo không đổi camp, chỉ ghi Nhật ký và gửi Telegram. Rất hợp để thử rule vài ngày trước khi cho nó tự hành động.', topic: 'rules' },
  skipLearning: { text: 'Camp mới hoặc vừa đổi lớn sẽ vào giai đoạn học. Đổi ngân sách lúc này khiến Facebook học lại từ đầu, nên rule sẽ bỏ qua các camp đó.', topic: 'protect' },
  dailyCap: { text: 'Tổng % ngân sách một camp được phép thay đổi trong một ngày do rule, tính trên ngân sách đầu ngày. Chống việc tăng/giảm dồn dập nhiều lần.', topic: 'protect' },
  killSwitch: { text: 'Khi tổng chi tiêu hôm nay của mọi camp vượt mức bạn đặt, tool tự tắt tất cả camp đang chạy (mỗi ngày tối đa một lần).', topic: 'protect' },
  interval: { text: 'Tool kiểm tra toàn bộ rule mỗi khoảng phút này. Tối thiểu 5 phút.', topic: 'rules' },
}
