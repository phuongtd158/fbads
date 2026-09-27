import {createRouter, createWebHashHistory} from 'vue-router'

const routes = [
    {
        path: '/',
        name: 'overview',
        component: () => import('./views/OverviewView.vue'),
        meta: {title: 'Tổng quan', sub: 'Theo dõi và điều khiển chiến dịch hôm nay'}
    },
    {
        path: '/schedules',
        name: 'schedules',
        component: () => import('./views/SchedulesView.vue'),
        meta: {title: 'Lịch tự động', sub: 'Hẹn giờ bật/tắt camp và đổi ngân sách'}
    },
    {
        path: '/rules',
        name: 'rules',
        component: () => import('./views/RulesView.vue'),
        meta: {title: 'Rule hiệu quả', sub: 'Tự tắt camp lỗ, tăng ngân sách camp tốt'}
    },
    {
        path: '/logs',
        name: 'logs',
        component: () => import('./views/LogsView.vue'),
        meta: {title: 'Nhật ký', sub: 'Mọi thay đổi tool đã thực hiện'}
    },
    {
        path: '/settings/:tab?',
        name: 'settings',
        component: () => import('./views/SettingsView.vue'),
        meta: {title: 'Cài đặt', sub: 'Kết nối Facebook, chế độ hoạt động, giao diện'}
    },
    {
        path: '/help/:topic?',
        name: 'help',
        component: () => import('./views/HelpView.vue'),
        meta: {title: 'Hướng dẫn', sub: 'Cách dùng từng chức năng, thuật ngữ và xử lý sự cố'}
    },
    {path: '/:pathMatch(.*)*', redirect: '/'},
]

export default createRouter({
    history: createWebHashHistory(),
    routes,
    scrollBehavior: () => ({top: 0}),
})
