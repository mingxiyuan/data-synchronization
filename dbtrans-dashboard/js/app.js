/**
 * DBTrans v3.4.0 - Core App (Global State, Router, Utilities)
 */

// ============================================================
// API CONFIG
// ============================================================
const API_BASE_URL = 'http://localhost:9005/transdb';

// ============================================================
// STORAGE KEYS
// ============================================================
const STORAGE_KEYS = {
    DB_CONNECTIONS: 'dbtrans_db_connections',
    MAPPING_RULES:   'dbtrans_mapping_rules',
    SYNC_CONFIG:     'dbtrans_sync_config',
    LOGS_DATA:       'dbtrans_logs_data'
};

// ============================================================
// DEFAULT DATA
// ============================================================
const DEFAULT_MAPPINGS = [
    { id: 1, srcTable: 'users', srcDb: 'prod_user_db (MySQL)', tgtTable: 'dim_users', tgtDb: 'dw_user_center (PostgreSQL)', strategy: 'incremental', filter: '' },
    { id: 2, srcTable: 'user_auth', srcDb: 'prod_user_db (MySQL)', tgtTable: 'fact_user_auth', tgtDb: 'dw_user_center (PostgreSQL)', strategy: 'full', filter: '' },
    { id: 3, srcTable: 'user_login_logs', srcDb: 'prod_user_db (MySQL)', tgtTable: 'stg_login_logs', tgtDb: 'dw_user_center (PostgreSQL)', strategy: 'cdc', filter: "dt >= '2026-01-15'" },
];

const DEFAULT_LOGS = [
    { id: 'JOB_39210', time: '2026-04-14 06:08:00', status: 'success', rows: '1,245,092', duration: '12m 45s', detail: '无异常上报', insertCnt: '1,200,000', updateCnt: '45,092', errorCnt: 0, srcDb: 'prod_user_db', tgtDb: 'dw_user_center' },
    { id: 'JOB_39188', time: '2026-04-13 18:08:00', status: 'fail', rows: '12,042', duration: '2m 10s', detail: 'Network timeout on target D8...', insertCnt: '10,500', updateCnt: '1,542', errorCnt: 54, srcDb: 'prod_user_db', tgtDb: 'dw_user_center' },
    { id: 'JOB_39158', time: '2026-04-13 06:08:00', status: 'success', rows: '1,192,231', duration: '11m 02s', detail: '无异常上报', insertCnt: '1,150,000', updateCnt: '42,231', errorCnt: 0, srcDb: 'prod_user_db', tgtDb: 'dw_user_center' },
    { id: 'JOB_39122', time: '2026-04-12 18:08:00', status: 'warning', rows: '842,002', duration: '15m 30s', detail: 'High latency detected (320ms)', insertCnt: '800,000', updateCnt: '42,002', errorCnt: 0, srcDb: 'prod_user_db', tgtDb: 'dw_user_center' },
];

const DEFAULT_DBS = [
    { id: 'db_1', name: '生产用户主库', type: 'mysql', url: 'jdbc:mysql://10.24.128.56:3306/prod_user_db', user: 'sync_admin', password: '******', remark: 'MySQL 8.0 生产环境', connected: true },
    { id: 'db_2', name: '数仓目标库', type: 'postgresql', url: 'jdbc:postgresql://pg-cloud.internal.net:5432/dw_user_center', user: 'etl_writer', password: '******', remark: 'PostgreSQL 14 数仓', connected: false },
    { id: 'db_3', name: 'Oracle业务库', type: 'oracle', url: 'jdbc:oracle:thin:@11.101.2.12:1521:gongsi', user: 'cjg_warn', password: '******', remark: 'Oracle 11g 业务系统', connected: true },
];

// ============================================================
// GLOBAL STATE
// ============================================================
const Store = {
    dbConnections: [],
    mappingRules:  [],
    logsData:      [],

    init() {
        this.dbConnections = this.load(STORAGE_KEYS.DB_CONNECTIONS) || [...DEFAULT_DBS];
        this.mappingRules   = this.load(STORAGE_KEYS.MAPPING_RULES)   || [...DEFAULT_MAPPINGS];
        this.logsData       = this.load(STORAGE_KEYS.LOGS_DATA)       || [...DEFAULT_LOGS];
    },

    load(key) {
        try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : null; } catch(e) { return null; }
    },

    save(key, data) {
        try { localStorage.setItem(key, JSON.stringify(data)); return true; } catch(e) { console.error('Save error:', e); return false; }
    }
};

// ============================================================
// CONSTANTS
// ============================================================
const DB_TYPE_INFO = {
    mysql:      { icon:Icons.dbTypeIcon('mysql'),     label:'MySQL',      defaultPort:3306,  cssClass:'mysql'      },
    postgresql: { icon:Icons.dbTypeIcon('postgresql'), label:'PostgreSQL', defaultPort:5432,  cssClass:'pg'         },
    oracle:     { icon:Icons.dbTypeIcon('oracle'),     label:'Oracle',     defaultPort:1521,  cssClass:'oracle'     },
    sqlserver:  { icon:Icons.dbTypeIcon('sqlserver'),  label:'SQL Server', defaultPort:1433,  cssClass:'sqlserver'  },
    mongodb:    { icon:Icons.dbTypeIcon('mongodb'),    label:'MongoDB',    defaultPort:27017, cssClass:'mongodb'    },
    dm:         { icon:Icons.dbTypeIcon('dm'),         label:'DM(达梦)',   defaultPort:5236,  cssClass:'dm'         },
};

const STRATEGY_LABELS = {
    incremental: { text: '增量同步', class: 'strategy-incremental' },
    full:        { text: '全量同步', class: 'strategy-full'        },
    cdc:         { text: 'CDC实时',  class: 'strategy-cdc'         }
};

const FETCH_MODE_LABELS = {
    parallel:  { text: '并行分页', class: 'fetch-parallel'  },
    streaming: { text: '流式读取', class: 'fetch-streaming' }
};

// ============================================================
// APP ROUTER & UTILITIES
// ============================================================
const App = {

    // 页面HTML缓存: pageId -> htmlString
    _pageHtmlCache: {},

    // pageId → URL路径映射
    _pagePathMap: {
        login:      'pages/login/index.html',
        dashboard:  'pages/dashboard/index.html',
        config:     'pages/config/index.html',
        dbmanage:    'pages/dbmanage/index.html',
        schedules:  'pages/schedules/index.html',
        logs:       'pages/logs/index.html',
        'log-detail': 'pages/logs/detail.html'
    },

    async loadPageHtml(pageId) {
        if (this._pageHtmlCache[pageId]) return this._pageHtmlCache[pageId];
        const url = this._pagePathMap[pageId] || `pages/${pageId}.html`;
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`加载页面 ${url} 失败: ${resp.status}`);
        const html = await resp.text();
        this._pageHtmlCache[pageId] = html;
        return html;
    },

    /** 执行容器中的 <script> 标签（innerHTML 不会自动执行脚本） */
    _executeScripts(container) {
        const scripts = container.querySelectorAll('script');
        scripts.forEach(oldScript => {
            const newScript = document.createElement('script');
            for (const attr of oldScript.attributes) {
                newScript.setAttribute(attr.name, attr.value);
            }
            newScript.textContent = oldScript.textContent;
            oldScript.replaceWith(newScript);
        });
    },

    _currentPage: 'login',

    _navMap: { dbmanage: 'nav-dbmanage', config: 'nav-config', schedules: 'nav-schedules', dashboard: 'nav-monitor', logs: 'nav-logs', 'log-detail': 'nav-logs' },

    async switchPage(page) {
        // 非登录页需鉴权
        if (page !== 'login' && !localStorage.getItem('token')) {
            page = 'login';
        }

        // 离开看板时清理定时器和图表
        if (this._currentPage === 'dashboard' && typeof Dashboard !== 'undefined') Dashboard.destroy();
        // 清理进度弹窗定时器, 防止切换页面后仍在轮询
        if (typeof Config !== 'undefined' && Config._progressTimer) {
            clearInterval(Config._progressTimer);
            Config._progressTimer = null;
        }
        var pm = document.getElementById('progressModal');
        if (pm) pm.remove();
        this._currentPage = page;

        // 登录页隐藏侧边栏
        const sidebar = document.querySelector('.sidebar');
        if (sidebar) sidebar.style.display = page === 'login' ? 'none' : '';

        // Hide all pages, deactivate all nav
        document.querySelectorAll('.page-tabs, .sidebar-item').forEach(el => el.classList.remove('active'));

        const pageEl = document.getElementById('page-' + page);
        if (!pageEl) { console.warn('Page not found:', page); return; }

        try {
            const html = await this.loadPageHtml(page);
            pageEl.innerHTML = html;
            Icons.init(pageEl);
            this._executeScripts(pageEl);
        } catch (e) {
            console.error('加载页面HTML失败:', e);
        }

        pageEl.classList.add('active');

        if (this._navMap[page]) {
            const navEl = document.getElementById(this._navMap[page]);
            if (navEl) navEl.classList.add('active');
        }

        // Page-specific init
        switch (page) {
            case 'dashboard': Dashboard.render(); break;
            case 'dbmanage': DbManage.render(); break;
            case 'config':   Config.render();   break;
            case 'schedules':Schedules.render(); break;
            case 'logs':      Logs.render();      break;
            case 'log-detail': /* handled by Logs.openDetail */ break;
        }
    },

    logout() {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        this.switchPage('login');
        App.showToast('已退出登录');
    },

    showToast(msg, type='success') {
        const container = document.getElementById('toastContainer');
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.innerHTML = `<span>${type==='success' ? Icons.check() : Icons.x()}</span><span>${msg}</span>`;
        container.appendChild(toast);
        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(100%)';
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    },

    showSaveIndicator() {
        const el = document.getElementById('saveIndicator');
        el.classList.add('show');
        setTimeout(() => el.classList.remove('show'), 2500);
    },

    toggleSidebar() {
        const sidebar = document.getElementById('appSidebar');
        if (!sidebar) return;
        const collapsed = sidebar.classList.toggle('collapsed');
        document.body.style.setProperty('--sidebar-current',
            collapsed ? 'var(--sidebar-width)' : 'var(--sidebar-expanded-width)');
        localStorage.setItem('sidebarCollapsed', collapsed ? '1' : '0');
        setTimeout(() => Icons.init(sidebar), 200);
    },

    _initSidebarState() {
        const sidebar = document.getElementById('appSidebar');
        if (!sidebar) return;
        const collapsed = localStorage.getItem('sidebarCollapsed') === '1';
        if (collapsed) {
            sidebar.classList.add('collapsed');
        }
        document.body.style.setProperty('--sidebar-current',
            collapsed ? 'var(--sidebar-width)' : 'var(--sidebar-expanded-width)');
    },

    openModal(id) {
        const m = document.getElementById(id);
        if (m) m.classList.add('show');
    },

    closeModal(id) {
        const m = document.getElementById(id);
        if (m) m.classList.remove('show');
    },

    getSelectedDbLabel(dbId) {
        if (!dbId) return null;
        const db = Store.dbConnections.find(d => d.id === dbId);
        if (!db) return null;
        const info = DB_TYPE_INFO[db.type] || DB_TYPE_INFO.mysql;
        // 从 url 解析出数据库名/模式名用于显示
        const parsed = Utils.parseJdbcUrl(db.type, db.url);
        const displayName = parsed.database || parsed.schema || parsed.host || '';
        return `${displayName} (${info.label})`;
    },

    updateTime() {
        const now = new Date();
        const pad = n => String(n).padStart(2, '0');
        document.getElementById('updateTime').textContent =
            `更新时间: ${now.getFullYear()}-${pad(now.getMonth()+1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    },

    addMinutes(timeStr, mins) {
        const d = new Date(timeStr.replace(' ', 'T'));
        d.setMinutes(d.getMinutes() + Math.floor(mins));
        d.setSeconds(d.getSeconds() + Math.round((mins % 1) * 60));
        return d.toISOString().replace('T', ' ').slice(0, 19);
    }
};

// Init overlay click-to-close after DOM ready
document.addEventListener('DOMContentLoaded', () => {
    Icons.init(document.body);
    App._initSidebarState();
    document.querySelectorAll('.modal-overlay').forEach(overlay => {
        overlay.addEventListener('click', e => {
            if (e.target === overlay) overlay.classList.remove('show');
        });
    });
});
