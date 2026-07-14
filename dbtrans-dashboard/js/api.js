/**
 * DBTrans v3.4.0 - API Service Layer
 * 后端接口封装，对接 localhost:9005
 */

const Api = {
    // ============ 通用请求方法 ============
    async request(url, options = {}) {
        const fullUrl = url.startsWith('http') ? url : `${API_BASE_URL}${url}`;
        const token = localStorage.getItem('token');
        const config = {
            headers: {
                'Content-Type': 'application/json',
                ...options.headers
            },
            ...options
        };
        if (token) config.headers['Authorization'] = token;

        if (config.body && typeof config.body === 'object') {
            config.body = JSON.stringify(config.body);
        }

        try {
            const response = await fetch(fullUrl, config);
            if (response.status === 401) {
                localStorage.removeItem('token');
                localStorage.removeItem('user');
                App.switchPage('login');
                throw new Error('登录已过期，请重新登录');
            }
            const data = await response.json();

            if (!response.ok || data.code !== 0) {
                throw new Error(data.message || `HTTP ${response.status}`);
            }
            return data;
        } catch (error) {
            console.error('API Error:', error);
            if (!error.message?.includes('登录已过期')) {
                App.showToast(error.message || '请求失败', 'error');
            }
            throw error;
        }
    },

    get(url) {
        return this.request(url, { method: 'GET' });
    },

    post(url, body) {
        return this.request(url, { method: 'POST', body });
    },

    put(url, body) {
        return this.request(url, { method: 'PUT', body });
    },

    delete(url) {
        return this.request(url, { method: 'DELETE' });
    },

    // ============ 数据库连接管理 ============
    async getDatabases() {
        const res = await this.get('/api/v1/databases');
        return res.data?.list || [];
    },

    async saveDatabase(data) {
        // 克隆数据，避免修改原始对象
        const payload = { ...data };
        
        // 如果有密码，尝试加密传输
        if (payload.password && payload.password !== '******') {
            const encrypted = await CryptoHelper.encryptPassword(payload.password);
            if (encrypted) {
                payload.password = encrypted.encrypted;
                payload.encrypted = true;
                payload.keyId = encrypted.keyId;
            }
            // 如果加密失败，仍然使用明文传输（依赖HTTPS）
        }

        if (payload.id) {
            // 编辑已有连接
            const res = await this.put(`/api/v1/databases/${payload.id}`, payload);
            return res.data;
        } else {
            // 新增连接
            const { id, ...newData } = payload;
            const res = await this.post('/api/v1/databases', newData);
            return res.data;
        }
    },

    async deleteDatabase(id) {
        await this.delete(`/api/v1/databases/${id}`);
        return { deleted_id: id };
    },

    async testConnection(data) {
        // 已保存的连接，直接传 id 让后端用存储的密码测试
        if (data.id) {
            const res = await this.post(`/api/v1/databases/${data.id}/test`);
            return res.data;
        }

        // 新增连接（尚未保存），前端传密码
        const payload = { ...data };
        if (payload.password) {
            const encrypted = await CryptoHelper.encryptPassword(payload.password);
            if (encrypted) {
                payload.password = encrypted.encrypted;
                payload.encrypted = true;
                payload.keyId = encrypted.keyId;
            }
        }
        
        const res = await this.post('/api/v1/databases/test', payload);
        return res.data;
    },

    // ============ 调度(Cron)管理 ============
    async getSchedules() {
        const res = await this.get('/api/v1/schedules');
        return res.data?.list || res.data || [];
    },

    async getSchedule(scheduleId) {
        const res = await this.get(`/api/v1/schedules/${scheduleId}`);
        return res.data;
    },

    async saveSchedule(data) {
        if (data.id) {
            const { id, ...updateData } = data;
            const res = await this.put(`/api/v1/schedules/${id}`, updateData);
            return res.data;
        } else {
            const res = await this.post('/api/v1/schedules', data);
            return res.data;
        }
    },

    async deleteSchedule(scheduleId) {
        await this.delete(`/api/v1/schedules/${scheduleId}`);
        return true;
    },

    async toggleSchedule(scheduleId, enabled) {
        const res = await this.put(`/api/v1/schedules/${scheduleId}`, { enabled });
        return res.data;
    },

    async triggerSchedule(scheduleId) {
        const res = await this.post(`/api/v1/schedules/${scheduleId}/trigger`);
        return res.data;
    },

    // ============ 同步任务管理 ============
    async getSyncTasks() {
        const res = await this.get('/api/v1/sync/tasks');
        return res.data?.list || res.data || [];
    },

    async getSyncTask(taskId) {
        const res = await this.get(`/api/v1/sync/tasks/${taskId}`);
        return res.data;
    },

    async saveSyncTask(data) {
        if (data.id) {
            const { id, ...updateData } = data;
            const res = await this.put(`/api/v1/sync/tasks/${id}`, updateData);
            return res.data;
        } else {
            const res = await this.post('/api/v1/sync/tasks', data);
            return res.data;
        }
    },

    async deleteSyncTask(taskId) {
        await this.delete(`/api/v1/sync/tasks/${taskId}`);
        return true;
    },

    async startSyncTask(taskId) {
        const res = await this.post(`/api/v1/sync/tasks/${taskId}/start`);
        return res.data;
    },

    async pauseSyncTask(taskId) {
        const res = await this.post(`/api/v1/sync/tasks/${taskId}/pause`);
        return res.data;
    },

    async stopSyncTask(taskId) {
        const res = await this.post(`/api/v1/sync/tasks/${taskId}/stop`);
        return res.data;
    },

    // ============ 同步配置(兼容旧接口) ============
    async getSyncConfig() {
        const res = await this.get('/api/v1/sync/config');
        return res.data;
    },

    async saveSyncConfig(data) {
        const res = await this.post('/api/v1/sync/config/deploy', data);
        return res.data;
    },

    async getTables(dbId, role = 'source') {
        const res = await this.get(`/api/v1/databases/${dbId}/tables?role=${role}`);
        return res.data?.tables || [];
    },

    async getSqlColumns(dbId, sql) {
        const res = await this.post(`/api/v1/databases/${dbId}/sql-columns`, { sql });
        return res.data?.columns || res.data || [];
    },

    async getTableColumns(dbId, tableName) {
        const res = await this.get(`/api/v1/databases/${dbId}/tables/${encodeURIComponent(tableName)}/columns`);
        const data = res.data;
        if (!data) return [];

        // 兼容多种返回格式
        // 1) 数组格式: { columns: [{name, type}, ...] } 或直接是数组
        if (Array.isArray(data)) return data;
        // 2) 对象数组: { columns: [...], list: [...], data: [...] }
        if (Array.isArray(data.columns)) return data.columns;
        if (Array.isArray(data.list)) return data.list;
        if (Array.isArray(data.data)) return data.data;

        // 3) 扁平对象格式: { "LSH": "VARCHAR2", "SFZMHM": "VARCHAR2", ... }
        if (typeof data === 'object' && !Array.isArray(data)) {
            const keys = Object.keys(data);
            if (keys.length > 0 && typeof data[keys[0]] === 'string') {
                // key=字段名, value=类型
                return Object.entries(data).map(([name, type]) => ({ name, type }));
            }
        }

        return [];
    },

    // ============ 看板 / 监控 ============
    async getDashboardOverview() {
        const res = await this.get('/api/v1/dashboard/overview');
        return res.data;
    },

    async getDashboardMetrics(minutes = 30, intervalSec = 300) {
        const res = await this.get(`/api/v1/dashboard/metrics/perf?minutes=${minutes}&interval_sec=${intervalSec}`);
        return res.data;
    },

    async getSyncDistribution() {
        const res = await this.get('/api/v1/dashboard/metrics/sync-distribution');
        return res.data;
    },

    async getRuntimeMetrics() {
        const res = await this.get('/api/v1/dashboard/metrics/runtime');
        return res.data;
    },

    // ============ SQL构建/测试 ============
    async sqlBuild(dbId, data) {
        const res = await this.post(`/api/v1/databases/${dbId}/sql-build`, data);
        return res.data;
    },

    async sqlTest(dbId, data) {
        const res = await this.post(`/api/v1/databases/${dbId}/sql-test`, data);
        return res.data;
    },

    // ============ 任务进度 ============
    async getTaskProgress(taskId) {
        const res = await this.get(`/api/v1/sync/tasks/${taskId}/progress`);
        return res.data;
    },

    async sourceMax(taskId) {
        const res = await this.post(`/api/v1/sync/tasks/${taskId}/source-max`);
        return res.data;
    },

    async batchSourceMax(taskIds) {
        const res = await this.post('/api/v1/sync/tasks/source-max', taskIds ? { taskIds } : {});
        return res.data;
    },

    async updateTaskMarker(taskId, markerValue) {
        const res = await this.put(`/api/v1/sync/tasks/${taskId}/marker`, { markerValue });
        return res.data;
    },

    // ============ 任务日志 ============
    async getLogs(taskId,page = 1, pageSize = 20, status = '', keyword = '') {
        const params = new URLSearchParams({taskId,page, page_size: pageSize });
        if (status && status !== 'all') params.append('status', status);
        if (keyword) params.append('keyword', keyword);
        const res = await this.get(`/api/v1/logs/tasks?${params}`);
        return res.data;
    },

    async getLogDetail(taskId) {
        const res = await this.get(`/api/v1/logs/tasks/${taskId}/detail`);
        return res.data;
    },

    async getTodaySummary() {
        const res = await this.get('/api/v1/logs/today-summary');
        return res.data;
    }
};
