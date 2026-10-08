/**
 * Lõi vòng tự động: EngineTicker được Spring gọi mỗi 30 giây, đi qua từng workspace và gọi lịch
 * (schedule/ScheduleRunner), rule (rule/RuleRunner), báo cáo và cảnh báo (report/), báo cáo công ty.
 * <p>
 * Đọc trước: EngineTicker. ActionExecutor là nơi duy nhất làm thay đổi thật (bật/tắt, đổi ngân sách, ghi nhật ký)
 * cho lịch, rule và hoàn tác. EngineState/WsState giữ trạng thái của engine theo workspace; EngineClock cho giờ
 * "bây giờ" (test đặt được giờ giả); KillSwitch là dừng khẩn; DailyMark là dấu theo ngày (bảng daily_marks).
 */
package com.fbads.engine;
