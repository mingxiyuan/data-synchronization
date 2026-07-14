/**
 * DBTrans — SVG Icon Library
 * Professional line-art icons (20×20, stroke-based, currentColor).
 * Zero dependencies.
 */
const Icons = (() => {

    // --------------- Navigation ---------------

    /** Database — stacked cylinders */
    function database(size = 20) {
        return svg(size, `<ellipse cx="10" cy="5" rx="7" ry="2.5" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M3 5v4c0 1.38 3.13 2.5 7 2.5s7-1.12 7-2.5V5" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M3 9v4c0 1.38 3.13 2.5 7 2.5s7-1.12 7-2.5V9" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M3 13v4c0 1.38 3.13 2.5 7 2.5s7-1.12 7-2.5v-4" fill="none" stroke="currentColor" stroke-width="1.5"/>`);
    }

    /** Sliders — horizontal adjustment controls */
    function sliders(size = 20) {
        return svg(size, `<line x1="3" y1="5" x2="17" y2="5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="12" cy="5" r="2.5" fill="currentColor"/>
            <line x1="3" y1="15" x2="17" y2="15" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="7" cy="15" r="2.5" fill="currentColor"/>`);
    }

    /** Clock */
    function clock(size = 20) {
        return svg(size, `<circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M10 6v5l3 3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** Activity — pulse / heartbeat monitor */
    function activity(size = 20) {
        return svg(size, `<path d="M2 10h3l2-7 3 14 3-10 2 4h3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** File Text — document with lines */
    function fileText(size = 20) {
        return svg(size, `<path d="M6 2v16a1 1 0 001 1h8a1 1 0 001-1V6l-4-4H7a1 1 0 00-1 1z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
            <path d="M12 2v4h4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
            <line x1="7" y1="10" x2="13" y2="10" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
            <line x1="7" y1="13" x2="13" y2="13" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>`);
    }

    /** Help Circle */
    function helpCircle(size = 20) {
        return svg(size, `<circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M8 8a2.2 2.2 0 014 0c0 2-3 2-3 3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="10" cy="15" r="0.8" fill="currentColor"/>`);
    }

    /** Log Out */
    function logOut(size = 20) {
        return svg(size, `<path d="M7 17H4a1 1 0 01-1-1V4a1 1 0 011-1h3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <path d="M13 14l4-4-4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M17 10H8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    // --------------- Actions ---------------

    /** Search — magnifying glass */
    function search(size = 20) {
        return svg(size, `<circle cx="9" cy="9" r="6" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M14 14l4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** Refresh / Sync */
    function refresh(size = 20) {
        return svg(size, `<path d="M3 4v5h5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M17 16v-5h-5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M16 7a7 7 0 00-9.9-1.4L3 9m14 2l-3.1 3.4A7 7 0 014 13" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** Plus — add new */
    function plus(size = 20) {
        return svg(size, `<line x1="10" y1="4" x2="10" y2="16" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <line x1="4" y1="10" x2="16" y2="10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** X — close / delete row */
    function x(size = 20) {
        return svg(size, `<line x1="6" y1="6" x2="14" y2="14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <line x1="14" y1="6" x2="6" y2="14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** Check */
    function check(size = 20) {
        return svg(size, `<path d="M5 11l3 3 7-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** Alert triangle */
    function alert(size = 20) {
        return svg(size, `<path d="M10 3L2 18h16L10 3z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
            <line x1="10" y1="9" x2="10" y2="12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="10" cy="15" r="0.8" fill="currentColor"/>`);
    }

    /** Edit — pencil */
    function edit(size = 20) {
        return svg(size, `<path d="M14 3l3 3-10 10H4v-3L14 3z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
            <line x1="12" y1="5" x2="15" y2="8" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>`);
    }

    /** Trash / Delete */
    function trash(size = 20) {
        return svg(size, `<path d="M4 5h12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <path d="M7 5V3a1 1 0 011-1h4a1 1 0 011 1v2m2 0v11a1 1 0 01-1 1H6a1 1 0 01-1-1V5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>`);
    }

    /** Eye — preview */
    function eye(size = 20) {
        return svg(size, `<path d="M2 10s3-6 8-6 8 6 8 6-3 6-8 6-8-6-8-6z" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <circle cx="10" cy="10" r="2.5" fill="none" stroke="currentColor" stroke-width="1.5"/>`);
    }

    /** Play */
    function play(size = 20) {
        return svg(size, `<path d="M6 3l12 7-12 7V3z" fill="currentColor" stroke="none"/>`);
    }

    /** Pause */
    function pause(size = 20) {
        return svg(size, `<rect x="5" y="3" width="3.5" height="14" rx="0.5" fill="currentColor"/>
            <rect x="11.5" y="3" width="3.5" height="14" rx="0.5" fill="currentColor"/>`);
    }

    /** Key — for PK indicator */
    function key(size = 20) {
        return svg(size, `<circle cx="7" cy="8" r="2.5" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M9 8h8l2 3-2 3h-2l-1-2h-2" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>`);
    }

    /** Folder */
    function folder(size = 20) {
        return svg(size, `<path d="M3 5a1 1 0 011-1h4l2 2h6a1 1 0 011 1v7a1 1 0 01-1 1H4a1 1 0 01-1-1V5z" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>`);
    }

    /** Download / Save */
    function save(size = 20) {
        return svg(size, `<path d="M5 17h10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <path d="M10 3v10M7 10l3 3 3-3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** Format / Align — code brackets */
    function format(size = 20) {
        return svg(size, `<path d="M7 5L3 10l4 5M13 5l4 5-4 5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** Zap / Lightning — quick action */
    function zap(size = 20) {
        return svg(size, `<path d="M12 2L7 11h4l-2 7 5-9h-4l2-7z" fill="currentColor" stroke="none"/>`);
    }

    /** Chevron Right — expand */
    function chevronRight(size = 20) {
        return svg(size, `<path d="M7 4l6 6-6 6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** Loader — spinning indicator */
    function loader(size = 20) {
        return svg(size, `<circle cx="10" cy="10" r="7" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="32" stroke-linecap="round" opacity="0.35"/>`);
    }

    /** Bar Chart */
    function barChart(size = 20) {
        return svg(size, `<rect x="3" y="9" width="3" height="8" rx="0.5" fill="currentColor"/>
            <rect x="8.5" y="5" width="3" height="12" rx="0.5" fill="currentColor"/>
            <rect x="14" y="1" width="3" height="16" rx="0.5" fill="currentColor"/>`);
    }

    /** Grid — dashboard / monitor */
    function monitor(size = 20) {
        return svg(size, `<rect x="3" y="3" width="14" height="11" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M7 17l2-3h2l2 3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    /** User */
    function user(size = 20) {
        return svg(size, `<circle cx="10" cy="7" r="3.5" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M4 18c0-3 2.7-5 6-5s6 2 6 5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** Lock */
    function lock(size = 20) {
        return svg(size, `<rect x="5" y="9" width="10" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M7 9V6a3 3 0 016 0v3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** Settings — gear (alternative to sliders) */
    function settings(size = 20) {
        return svg(size, `<circle cx="10" cy="10" r="3" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <path d="M10 2v2m0 12v2M2 10h2m12 0h2M4.5 4.5l1.4 1.4m8.2 8.2l1.4 1.4m-12.8 0l1.4-1.4m8.2-8.2l1.4-1.4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>`);
    }

    /** Info */
    function info(size = 20) {
        return svg(size, `<circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" stroke-width="1.5"/>
            <line x1="10" y1="8" x2="10" y2="14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            <circle cx="10" cy="5.5" r="0.8" fill="currentColor"/>`);
    }

    /** Arrow Left — back navigation */
    function arrowLeft(size = 20) {
        return svg(size, `<path d="M16 10H4m0 0l5-5m-5 5l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`);
    }

    // --------------- Database type icons (colored circles) ---------------

    const DB_COLORS = {
        mysql:     { bg: 'rgba(0,117,143,0.12)',  fg: '#00758f' },
        postgresql:{ bg: 'rgba(51,103,145,0.12)', fg: '#336791' },
        oracle:    { bg: 'rgba(199,70,52,0.12)',  fg: '#c74634' },
        sqlserver: { bg: 'rgba(204,41,39,0.12)',  fg: '#cc2927' },
        mongodb:   { bg: 'rgba(77,179,61,0.12)',  fg: '#4db33d' },
        dm:        { bg: 'rgba(220,53,69,0.12)',  fg: '#dc3545' },
    };

    function dbTypeIcon(type, size = 20) {
        const c = DB_COLORS[type] || { bg: '#f0f0f0', fg: '#333' };
        const label = { mysql:'My', postgresql:'Pg', oracle:'Or', sqlserver:'Ms', mongodb:'Mo', dm:'DM' }[type] || '?';
        return `<span style="display:inline-flex;align-items:center;justify-content:center;width:${size}px;height:${size}px;border-radius:6px;background:${c.bg};color:${c.fg};font-size:${size < 24 ? '10px' : '12px'};font-weight:700;font-family:-apple-system,BlinkMacSystemFont,sans-serif;letter-spacing:-0.02em;flex-shrink:0;">${label}</span>`;
    }

    // --------------- Helpers ---------------

    function svg(size, inner) {
        return `<svg width="${size}" height="${size}" viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" style="flex-shrink:0;display:inline-block;vertical-align:middle;">${inner}</svg>`;
    }

    // --------------- Init — scan & replace data-icon elements ---------------

    const ICON_MAP = {
        'database': database, 'sliders': sliders, 'clock': clock, 'activity': activity,
        'file-text': fileText, 'help-circle': helpCircle, 'log-out': logOut,
        'search': search, 'refresh': refresh, 'plus': plus, 'x': x, 'check': check,
        'alert': alert, 'edit': edit, 'trash': trash, 'eye': eye, 'play': play,
        'pause': pause, 'key': key, 'folder': folder, 'save': save,
        'format': format, 'zap': zap, 'chevron-right': chevronRight,
        'loader': loader, 'bar-chart': barChart, 'monitor': monitor,
        'user': user, 'lock': lock, 'settings': settings, 'info': info,
        'arrow-left': arrowLeft,
    };

    function init(container) {
        if (!container) container = document;
        container.querySelectorAll('[data-icon]').forEach(el => {
            const name = el.getAttribute('data-icon');
            const size = parseInt(el.getAttribute('data-icon-size') || '20');
            const fn = ICON_MAP[name];
            if (fn) {
                el.innerHTML = fn(size);
                el.classList.add('icon-el');
            }
        });
        // Also handle data-db-icon for db type badges
        container.querySelectorAll('[data-db-icon]').forEach(el => {
            const type = el.getAttribute('data-db-icon');
            const size = parseInt(el.getAttribute('data-icon-size') || '20');
            el.innerHTML = dbTypeIcon(type, size);
            el.classList.add('icon-el');
        });
    }

    // --------------- Exports ---------------

    return {
        // Nav
        database, sliders, clock, activity, fileText, helpCircle, logOut,
        // Actions
        search, refresh, plus, x, check, alert, edit, trash, eye, play, pause,
        key, folder, save, format, zap, chevronRight, loader, barChart, monitor,
        user, lock, settings, info, arrowLeft,
        // Helpers
        dbTypeIcon, svg, init,
    };
})();
