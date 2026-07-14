/**
 * DBTrans v3.6.0 - Common Utilities
 * 通用工具方法，供各页面共享
 */

const Utils = {

    /** HTML 转义，防止 XSS */
    esc(s) {
        const d = document.createElement('div');
        d.textContent = s || '';
        return d.innerHTML;
    },

    /** 正则特殊字符转义 */
    regEscape(s) {
        return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    },

    /** 根据任务状态返回 { text, html } */
    getStatusInfo(status) {
        const map = {
            running:  { text: '运行中', html: `<span class="status-tag success">${Icons.play()} 运行中</span>` },
            stopped:  { text: '已停止', html: `<span class="status-tag fail">${Icons.x()} 已停止</span>` },
            error:    { text: '异常',   html: `<span class="status-tag error">${Icons.alert()} 异常</span>` },
            pending:  { text: '待启动', html: `<span class="status-tag pending">${Icons.clock()} 待启动</span>` },
            paused:   { text: '已暂停', html: `<span class="status-tag warn">${Icons.pause()} 已暂停</span>` },
        };
        return map[status] || map['stopped'];
    },

    /** 格式化 ISO 日期字符串为本地时间显示 */
    formatTime(timeStr) {
        return timeStr ? timeStr.replace('T', ' ').substring(0, 19) : '-';
    },

    /** 获取任务的分组名 */
    getTaskGroup(task) {
        return task.task_group || '';
    },

    /** 将任务列表按分组归类，返回 { groups: Map<groupName, tasks[]>, noGroup: tasks[] } */
    buildGroupMap(taskList) {
        const groups = new Map();
        const noGroup = [];
        (taskList || []).forEach(task => {
            const g = this.getTaskGroup(task);
            if (g) {
                if (!groups.has(g)) groups.set(g, []);
                groups.get(g).push(task);
            } else {
                noGroup.push(task);
            }
        });
        return { groups, noGroup };
    },

    /** 构建带库名的表标签 */
    buildTableLabel(dbName, tableName) {
        return (dbName ? `${dbName}.` : '') + (tableName || '-');
    },

    /** 各数据库类型 JDBC URL 配置 */
    DB_CONFIGS: {
        mysql:      { label:'MySQL',      defaultPort:3306,  showDatabase:true,  showSchema:false, urlFormat:'jdbc:mysql://{host}:{port}/{database}' },
        postgresql: { label:'PostgreSQL', defaultPort:5432,  showDatabase:true,  showSchema:false, urlFormat:'jdbc:postgresql://{host}:{port}/{database}' },
        oracle:     { label:'Oracle',     defaultPort:1521,  showDatabase:true,  showSchema:false, urlFormat:'jdbc:oracle:thin:@{host}:{port}:{database}' },
        sqlserver:  { label:'SQL Server', defaultPort:1433,  showDatabase:true,  showSchema:false, urlFormat:'jdbc:sqlserver://{host}:{port};databaseName={database}' },
        mongodb:    { label:'MongoDB',    defaultPort:27017, showDatabase:true,  showSchema:false, urlFormat:'jdbc:mongodb://{host}:{port}/{database}' },
        dm:         { label:'DM(达梦)',   defaultPort:5236,  showDatabase:false, showSchema:true,  urlFormat:'jdbc:dm://{host}:{port}?SCHEMA={schema}' },
    },

    /** 根据表单字段和数据库类型，拼接生成JDBC URL */
    buildJdbcUrl(type, host, port, database, schema) {
        const cfg = this.DB_CONFIGS[type];
        if (!cfg) return '';
        let url = cfg.urlFormat
            .replace('{host}', host || '')
            .replace('{port}', port || '');
        if (cfg.showDatabase) {
            url = url.replace('{database}', database || '');
        }
        if (cfg.showSchema) {
            if (schema) {
                url = url.replace('{schema}', schema);
            } else {
                url = url.replace('?SCHEMA={schema}', '');
            }
        }
        return url;
    },

    /** 从JDBC URL中解析出 host/port/database/schema */
    parseJdbcUrl(type, url) {
        const result = { host: '', port: '', database: '', schema: '' };
        if (!url) return result;
        try {
            if (type === 'mysql') {
                const m = url.match(/jdbc:mysql:\/\/([^:]+):(\d+)\/(.+)/);
                if (m) { result.host = m[1]; result.port = m[2]; result.database = m[3]; }
            } else if (type === 'postgresql') {
                const m = url.match(/jdbc:postgresql:\/\/([^:]+):(\d+)\/(.+)/);
                if (m) { result.host = m[1]; result.port = m[2]; result.database = m[3]; }
            } else if (type === 'oracle') {
                const m1 = url.match(/jdbc:oracle:thin:@([^:]+):(\d+):(.+)/);
                const m2 = url.match(/jdbc:oracle:thin:@\/\/([^:]+):(\d+)\/(.+)/);
                const m = m1 || m2;
                if (m) { result.host = m[1]; result.port = m[2]; result.database = m[3]; }
            } else if (type === 'sqlserver') {
                const m1 = url.match(/jdbc:sqlserver:\/\/([^:]+):(\d+);databaseName=(.+)/);
                const m2 = url.match(/jdbc:sqlserver:\/\/([^:]+):(\d+)/);
                if (m1) { result.host = m1[1]; result.port = m1[2]; result.database = m1[3]; }
                else if (m2) { result.host = m2[1]; result.port = m2[2]; }
            } else if (type === 'mongodb') {
                const m = url.match(/jdbc:mongodb:\/\/([^:]+):(\d+)\/(.+)/);
                if (m) { result.host = m[1]; result.port = m[2]; result.database = m[3]; }
            } else if (type === 'dm') {
                const m1 = url.match(/jdbc:dm:\/\/([^:]+):(\d+)\?SCHEMA=(.+)/);
                const m2 = url.match(/jdbc:dm:\/\/([^:]+):(\d+)/);
                if (m1) { result.host = m1[1]; result.port = m1[2]; result.schema = m1[3]; }
                else if (m2) { result.host = m2[1]; result.port = m2[2]; }
            }
        } catch(e) { /* ignore */ }
        return result;
    }
};
