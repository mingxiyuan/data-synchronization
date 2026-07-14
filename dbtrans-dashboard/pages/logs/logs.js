var Logs = {
    currentStatus: '',
    currentKeyword: '',
    _taskList: [],
    _currentTaskId: null,
    _taskLogPage: 1,
    _currentGroupName: null,

    async render() {
        this._currentGroupName = null;
        this._currentTaskId = null;
        this._taskLogPage = 1;
        await this.loadTasks();
    },

    async loadTasks() {
        try {
            const tasks = await Api.getSyncTasks();
            this._taskList = Array.isArray(tasks) ? tasks : [];
            this._taskList.forEach(t => { t.id = String(t.id); });
            this._renderLogView();
            this._updateStatsFromTasks();
        } catch (error) {
            document.getElementById('taskListBody').innerHTML =
                `<tr><td colspan="7" style="text-align:center;padding:30px;color:var(--accent-red)">加载失败: ${Utils.esc(error.message)}</td></tr>`;
        }
    },

    _getTaskGroup(task) { return Utils.getTaskGroup(task); },

    _buildGroupMap() { return Utils.buildGroupMap(this._taskList); },

    _renderLogView() {
        const folderView = document.getElementById('logGroupFolderView');
        const tableView = document.getElementById('taskListView');
        const logView = document.getElementById('taskLogView');
        if (!folderView || !tableView) return;

        if (this._currentTaskId) {
            folderView.style.display = 'none';
            tableView.style.display = 'none';
            logView.style.display = '';
            return;
        }

        logView.style.display = 'none';
        const { groups, noGroup } = this._buildGroupMap();

        if (this.currentKeyword) {
            folderView.style.display = 'none';
            tableView.style.display = '';
            const kw = this.currentKeyword.toLowerCase();
            const filtered = this._taskList.filter(t =>
                (t.src_table || '').toLowerCase().includes(kw) ||
                (t.tgt_table || '').toLowerCase().includes(kw) ||
                (t.name || '').toLowerCase().includes(kw) ||
                this._getTaskGroup(t).toLowerCase().includes(kw)
            );
            document.getElementById('logGroupLabel').textContent = `搜索结果: "${this.currentKeyword}"`;
            this._renderTaskTable(filtered);
            return;
        }

        if (this._currentGroupName !== null) {
            folderView.style.display = 'none';
            tableView.style.display = '';
            const tasks = this._currentGroupName === '__nogroup__' ? noGroup : (groups.get(this._currentGroupName) || []);
            document.getElementById('logGroupLabel').textContent = this._currentGroupName === '__nogroup__' ? '未分组任务' : this._currentGroupName;
            this._renderTaskTable(tasks);
            return;
        }

        folderView.style.display = '';
        tableView.style.display = 'none';
        this._renderLogGroupFolderView(groups, noGroup);
    },

    _renderLogGroupFolderView(groups, noGroup) {
        const folderView = document.getElementById('logGroupFolderView');
        if (!folderView) return;

        folderView.innerHTML = '';

        if (!this._taskList.length) {
            folderView.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-muted)">暂无同步任务</div>';
            return;
        }

        const cardTpl = document.getElementById('tpl-logs-folder-card');
        const compactTpl = document.getElementById('tpl-logs-compact-task');

        if (groups.size > 0) {
            const grid = document.createElement('div');
            grid.className = 'group-folder-grid';
            groups.forEach((tasks, groupName) => {
                const card = cardTpl.content.firstElementChild.cloneNode(true);
                card.onclick = function() { Logs._enterLogGroup(groupName); };
                card.querySelector('.group-folder-name').textContent = groupName;
                const runningCount = tasks.filter(t => t.status === 'running').length;
                let metaHtml = `<span>${tasks.length} 个任务</span>`;
                if (runningCount) metaHtml += `<span><span class="running-dot"></span> ${runningCount} 运行中</span>`;
                card.querySelector('.group-folder-meta').innerHTML = metaHtml;
                let totalSuccess = 0, totalFail = 0;
                tasks.forEach(t => { totalSuccess += (t.success_count || 0); totalFail += (t.fail_count || 0); });
                card.querySelector('.group-folder-stats').innerHTML =
                    `<span style="color:var(--accent-green)">${totalSuccess}</span> 成功 / <span style="color:var(--accent-red)">${totalFail}</span> 失败`;
                grid.appendChild(card);
            });
            folderView.appendChild(grid);
        }

        if (noGroup.length > 0) {
            if (groups.size > 0) {
                const section = document.createElement('div');
                section.className = 'group-ungrouped-section';
                section.innerHTML = `<div class="group-ungrouped-header">
                    <span style="font-size:16px;">${Icons.fileText()}</span>
                    <span>未分组 (${noGroup.length} 个任务)</span>
                    <span class="detail-link" style="margin-left:auto;" onclick="Logs._enterLogGroup('__nogroup__')">查看全部 →</span>
                </div>`;
                const maxShow = Math.min(noGroup.length, 3);
                for (let i = 0; i < maxShow; i++) {
                    const task = noGroup[i];
                    const row = compactTpl.content.firstElementChild.cloneNode(true);
                    row.querySelector('.compact-src').textContent = Utils.buildTableLabel(task.source_db_name, task.src_table);
                    row.querySelector('.compact-tgt').textContent = Utils.buildTableLabel(task.target_db_name, task.tgt_table);
                    if (task.src_sql) row.querySelector('.compact-sql-badge').style.display = '';
                    row.querySelector('.compact-status').innerHTML = Utils.getStatusInfo(task.status).html;
                    section.appendChild(row);
                }
                if (noGroup.length > 3) {
                    const more = document.createElement('div');
                    more.style.cssText = 'text-align:center;padding:8px;font-size:12px;color:var(--text-muted);';
                    more.innerHTML = `还有 ${noGroup.length - 3} 个任务，<span class="detail-link" onclick="Logs._enterLogGroup('__nogroup__')">查看全部</span>`;
                    section.appendChild(more);
                }
                folderView.appendChild(section);
            } else {
                const div = document.createElement('div');
                div.className = 'table-container';
                div.innerHTML = '<table><thead><tr><th>同步任务</th><th>上次同步时间</th><th>同步次数</th><th>总成功</th><th>总失败</th><th>状态</th><th>操作</th></tr></thead><tbody></tbody></table>';
                const tbody = div.querySelector('tbody');
                noGroup.forEach(task => tbody.appendChild(this._buildLogTaskRow(task)));
                folderView.appendChild(div);
            }
        }
    },

    _buildLogTaskRow(task) {
        const tpl = document.getElementById('tpl-logs-task-row');
        const row = tpl.content.firstElementChild.cloneNode(true);
        const srcLabel = Utils.buildTableLabel(task.source_db_name, task.src_table);
        const tgtLabel = Utils.buildTableLabel(task.target_db_name, task.tgt_table);
        const isSql = !!task.src_sql;
        let labelHtml = Utils.esc(srcLabel) + (isSql ? '<span style="font-size:10px;color:var(--accent-purple);background:rgba(163,113,247,.12);padding:1px 6px;border-radius:3px;margin-left:4px;">SQL</span>' : '') + ' → ' + Utils.esc(tgtLabel);
        row.querySelector('.task-label-div').innerHTML = labelHtml;
        if (task.schedule_name) {
            const schedDiv = row.querySelector('.task-sched-div');
            schedDiv.style.display = '';
            schedDiv.textContent = '调度: ' + task.schedule_name;
        }
        row.querySelector('.task-last-time').textContent = Utils.formatTime(task.last_sync_time);
        row.querySelector('.task-sync-count').innerHTML = (task.sync_count || 0) + '<span class="unit" style="font-size:11px;margin-left:4px;">次</span>';
        row.querySelector('.task-success-count').textContent = task.success_count || 0;
        const failEl = row.querySelector('.task-fail-count');
        failEl.textContent = task.fail_count || 0;
        failEl.style.color = (task.fail_count || 0) > 0 ? 'var(--accent-red)' : 'var(--text-muted)';
        failEl.style.fontWeight = '500';
        row.querySelector('.task-status').innerHTML = Utils.getStatusInfo(task.status).html;
        const tid = Utils.esc(task.id);
        const title = Utils.esc(srcLabel + ' → ' + tgtLabel);
        row.querySelector('.task-detail-link').onclick = function() { Logs.openTaskLogs(tid, title); };
        return row;
    },

    _enterLogGroup(groupName) {
        this._currentGroupName = groupName;
        this._renderLogView();
    },

    _backToLogGroupList() {
        this._currentGroupName = null;
        this.currentKeyword = '';
        const si = document.getElementById('logSearchInput');
        if (si) si.value = '';
        this._renderLogView();
    },

    _renderTaskTable(list) {
        if (this.currentStatus === 'fail') list = list.filter(t => (t.fail_count || 0) > 0);
        if (this.currentStatus === 'success') list = list.filter(t => (t.fail_count || 0) === 0 && (t.sync_count || 0) > 0);

        const tbody = document.getElementById('taskListBody');
        tbody.innerHTML = '';
        if (!list.length) {
            tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:30px;color:var(--text-muted)">暂无匹配的任务</td></tr>';
        } else {
            list.forEach(task => tbody.appendChild(this._buildLogTaskRow(task)));
        }
        document.getElementById('logCountInfo').textContent = `共 ${list.length} 个任务`;
    },

    async openTaskLogs(taskId, title) {
        this._currentTaskId = taskId;
        this._taskLogPage = 1;
        document.getElementById('taskLogTitle').textContent = `— ${title}`;
        this._renderLogView();
        await this.loadTaskLogs();
    },

    _backToTaskList() {
        this._currentTaskId = null;
        this._renderLogView();
    },

    async loadTaskLogs() {
        if (!this._currentTaskId) return;
        try {
            const data = await Api.getLogs(this._currentTaskId, this._taskLogPage || 1, 15, '', '');
            const list = Array.isArray(data?.list) ? data.list : [];
            const pagination = data?.pagination || {};

            const tbody = document.getElementById('taskLogBody');
            tbody.innerHTML = '';
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--text-muted)">该任务暂无执行记录</td></tr>';
            } else {
                const tpl = document.getElementById('tpl-logs-log-row');
                list.forEach(log => {
                    const row = tpl.content.firstElementChild.cloneNode(true);
                    row.querySelector('.log-time').textContent = Utils.formatTime(log.exec_time);
                    const cls = { success:'success', fail:'fail', warning:'warning', running:'info' }[log.status] || 'info';
                    const txt = { success:'SUCCESS', fail:'FAIL', warning:'WARNING', running:'RUNNING' }[log.status] || (log.status||'').toUpperCase();
                    row.querySelector('.log-status').innerHTML = `<span class="status-tag ${cls}">${txt}</span>`;
                    row.querySelector('.log-rows').textContent = (log.rows_total ? log.rows_total.toLocaleString() : '-') + ' rows';
                    row.querySelector('.log-duration').textContent = log.duration_display || '-';
                    row.querySelector('.log-detail-link').onclick = function() { Logs.openDetail(log.id); };
                    tbody.appendChild(row);
                });
            }
            this._renderTaskLogPagination(pagination);
        } catch (error) {
            document.getElementById('taskLogBody').innerHTML =
                `<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--accent-red)">加载失败: ${Utils.esc(error.message)}</td></tr>`;
        }
    },

    _renderTaskLogPagination(pagination) {
        const { page, total_pages, total } = pagination;
        const info = document.getElementById('taskLogCountInfo');
        const pagEl = document.getElementById('taskLogPagination');
        if (info) info.textContent = `共 ${total || 0} 条执行记录`;
        if (!pagEl) return;

        let html = '';
        if (page > 1) html += `<span class="page-btn" onclick="Logs._goTaskLogPage(${page-1})">«</span>`;
        for (let i = Math.max(1, page-3); i <= Math.min(total_pages||page, page+3); i++) {
            html += `<span class="page-btn ${i===page?'active':''}" onclick="Logs._goTaskLogPage(${i})">${i}</span>`;
        }
        if (page < (total_pages||1)) html += `<span class="page-btn" onclick="Logs._goTaskLogPage(${page+1})">»</span>`;
        pagEl.innerHTML = html;
    },

    _goTaskLogPage(page) { this._taskLogPage = page; this.loadTaskLogs(); },

    _updateStatsFromTasks() {
        const tasks = this._taskList || [];
        let totalSuccess = 0, totalFail = 0;
        tasks.forEach(t => {
            totalSuccess += (t.success_count || 0);
            totalFail += (t.fail_count || 0);
        });
        const total = totalSuccess + totalFail;
        const rate = total > 0 ? ((totalSuccess / total) * 100).toFixed(1) : '100.0';

        const setStat = (elId, numText, unitText) => {
            const el = document.getElementById(elId);
            if (!el) return;
            const numEl = el.querySelector('.stat-num');
            if (numEl) numEl.textContent = numText;
            const unitEl = el.querySelector('.unit');
            if (unitEl) unitEl.textContent = unitText;
        };
        setStat('statTaskCount', tasks.length, '个任务');
        setStat('statSuccessError', totalSuccess + ' / ' + totalFail, `次 (${rate}%)`);
        setStat('statErrorCount', totalFail, '次记录');
    },

    changeStatus(status) {
        this.currentStatus = status;
        this._renderLogView();
    },

    filterTable(val) {
        this.currentKeyword = val.trim();
        this._renderLogView();
    },

    async openDetail(jobId) {
        try {
            const detail = await Api.getLogDetail(jobId);
            if (!detail) { App.showToast('未找到任务详情', 'error'); return; }
            await App.switchPage('log-detail');
            LogDetail.render(detail);
        } catch (e) { App.showToast('加载详情失败', 'error'); }
    },

    returnFromDetail() {
        document.querySelectorAll('.page-tabs, .sidebar-item').forEach(el => el.classList.remove('active'));
        const pageEl = document.getElementById('page-logs');
        if (pageEl) pageEl.classList.add('active');
        const navEl = document.getElementById('nav-logs');
        if (navEl) navEl.classList.add('active');
        App._currentPage = 'logs';

        if (this._currentTaskId) {
            this._renderLogView();
            this.loadTaskLogs();
        } else {
            this.render();
        }
    }
};
