var Config = {

    _sourceFields: [],
    _targetFields: [],
    _fieldMappings: [],
    _taskList: [],
    _editingTaskId: null,
    _editingSrcSql: null,
    _currentIncrField: '',
    _currentGroupName: null,
    _taskSearchKeyword: '',
    _sourceMode: 'table',
    _sqlEditor: null,

    async render() {
        this._editingTaskId = null;
        this._currentGroupName = null;
        this._sourceMode = 'table';
        this._sqlEditor = null;
        this._editingSrcSql = null;
        await this.refreshDbSelects();
        this._loadSyncTasks();
    },

    // ---- 同步任务列表 ----

    async _loadSyncTasks() {
        try {
            const tasks = await Api.getSyncTasks();
            this._taskList = Array.isArray(tasks) ? tasks : [];
            this._taskList.forEach(t => {
                t.id = String(t.id);
                if (t.source_db_id) t.source_db_id = String(t.source_db_id);
                if (t.target_db_id) t.target_db_id = String(t.target_db_id);
                if (t.schedule_id) t.schedule_id = String(t.schedule_id);
            });
            this._renderTaskList();
        } catch (error) {
            console.error('加载同步任务失败:', error);
            this._renderTaskListError(error.message || '加载失败');
        }
    },

    _refreshTaskList() { this._loadSyncTasks(); },

    async _querySourceMax(taskId, row) {
        const cell = row.querySelector('.task-source-max');
        cell.textContent = '查询中...';
        try {
            const r = await Api.sourceMax(taskId);
            cell.textContent = r.sourceMaxValue || '-';
        } catch(e) {
            cell.textContent = '失败';
        }
    },

    _editMarker(taskId, row) {
        const current = row.querySelector('.task-last-marker').textContent;
        const id = 'editMarkerDialog';
        let m = document.getElementById(id);
        if (m) m.remove();
        m = document.createElement('div');
        m.id = id;
        m.className = 'modal-overlay show';
        m.innerHTML = `
        <div class="modal-box" style="max-width:420px;">
            <div class="modal-header"><span class="modal-title">修改同步至</span><button class="modal-close" type="button" onclick="document.getElementById('${id}').remove()">${Icons.x()}</button></div>
            <div class="modal-body">
                <div class="form-group">
                    <label class="form-label" style="color:var(--text-primary);font-weight:500;">当前值: ${Utils.esc(current)}</label>
                    <input class="form-input" id="editMarkerValue" placeholder="输入新的标记值, 如: 2024-12-11 12:00:00" value="${Utils.esc(current !== '-' ? current : '')}" style="width:100%;box-sizing:border-box;">
                </div>
                <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px;">
                    <button class="btn" type="button" onclick="document.getElementById('${id}').remove()">取消</button>
                    <button class="btn btn-primary" type="button" id="editMarkerConfirm">确定</button>
                </div>
            </div>
        </div>`;
        document.body.appendChild(m);
        m.onclick = e => { if (e.target === m) m.remove(); };
        const self = this;
        document.getElementById('editMarkerConfirm').onclick = async function() {
            const val = document.getElementById('editMarkerValue').value.trim();
            if (!val) { App.showToast('请输入标记值', 'error'); return; }
            m.remove();
            try {
                await Api.updateTaskMarker(taskId, val);
                row.querySelector('.task-last-marker').textContent = val;
                App.showToast('同步至已更新', 'success');
            } catch(e) {
                App.showToast('更新失败: ' + (e.message || ''), 'error');
            }
        };
    },

    _progressTimer: null,  // 全局唯一的进度轮询定时器

    _showProgress(taskId) {
        // 停掉上一个进度弹窗的定时器, 防止多个任务同时轮询
        if (this._progressTimer) { clearInterval(this._progressTimer); this._progressTimer = null; }
        var task = this._taskList.find(function(t) { return t.id === taskId; });
        var taskName = task ? (task.src_table || task.name || taskId) : taskId;
        var id = 'progressModal';
        var m = document.getElementById(id);
        if (m) m.remove();
        m = document.createElement('div');
        m.id = id;
        m.className = 'modal-overlay show';
        m.innerHTML = `<div class="modal-box" style="max-width:500px;">
            <div class="modal-header"><span class="modal-title">任务进度</span><button class="modal-close" type="button">${Icons.x()}</button></div>` +
            '<div class="modal-body" style="padding:20px;">' +
            '<div style="margin-bottom:8px;font-size:13px;font-weight:600;">' + Utils.esc(taskName) + '</div>' +
            '<div class="progress-bar" style="height:20px;border-radius:6px;margin-bottom:12px;">' +
            '<div class="progress-fill" id="progBar" style="width:0%;background:var(--accent-primary);border-radius:6px;transition:width .3s;"></div></div>' +
            '<div style="font-size:13px;"><span id="progPct">0%</span></div>' +
            '<div style="font-size:12px;color:#1e293b;margin-top:8px;">' +
            '<span id="progRows">- / - 行</span>' +
            '<span id="progFail" style="display:none;color:var(--accent-red);"> &nbsp;|&nbsp; 失败 <span id="progFailCnt">0</span> 批</span>' +
            ' &nbsp;|&nbsp; 耗时 <span id="progElapsed">-</span>' +
            ' &nbsp;|&nbsp; <span id="progSpeed">- 行/秒</span>' +
            ' &nbsp;|&nbsp; 预计剩余 <span id="progEta">-</span></div>' +
            '<div id="progDone" style="display:none;margin-top:12px;font-size:13px;font-weight:600;"></div>' +
            '</div></div>';
        document.body.appendChild(m);
        m.onclick = function(e) { if (e.target === m) m.remove(); };
        m.querySelector('.modal-close').onclick = function() { m.remove(); };

        var self = this;
        var lastTotal = 0, lastDone = 0;
        var speedSamples = [];  // [{done, time}] 近10秒滑动窗口
        function update() {
            Api.getTaskProgress(taskId).then(function(p) {
                var el = document.getElementById('progBar');
                if (!el) return;
                var total = Math.max(lastTotal, p.totalRows || 0);
                var done = Math.max(lastDone, p.successRows || 0);
                lastTotal = total; lastDone = done;
                var now = Date.now();
                speedSamples.push({ d: done, t: now });
                while (speedSamples.length > 1 && now - speedSamples[0].t > 10000) speedSamples.shift();
                var spd = '—';
                if (speedSamples.length >= 2) {
                    var f = speedSamples[0], l = speedSamples[speedSamples.length - 1];
                    var ds = Math.max(1, (l.t - f.t) / 1000);
                    spd = Math.round((l.d - f.d) / ds).toLocaleString() + ' 行/秒';
                }
                var pct = total > 0 ? Math.min(100, Math.round(done * 100 / total)) : 0;
                el.style.width = pct + '%';
                document.getElementById('progPct').textContent = pct + '%';
                document.getElementById('progRows').textContent = done.toLocaleString() + ' / ' + total.toLocaleString() + ' 行';
                var failCnt = p.failRows || 0;
                var failEl = document.getElementById('progFail');
                if (failCnt > 0) {
                    failEl.style.display = '';
                    document.getElementById('progFailCnt').textContent = failCnt;
                } else {
                    failEl.style.display = 'none';
                }
                var sec = Math.round((p.elapsedMs || 0) / 1000);
                var min = Math.floor(sec / 60);
                document.getElementById('progElapsed').textContent = min + 'm' + (sec % 60) + 's';
                document.getElementById('progSpeed').textContent = spd;
                var eta = p.estimatedRemainingMs;
                if (eta && eta > 0) {
                    var etaSec = Math.round(eta / 1000);
                    document.getElementById('progEta').textContent = Math.floor(etaSec / 60) + 'm' + (etaSec % 60) + 's';
                }
                if (p.status === 'COMPLETED') {
                    document.getElementById('progDone').style.display = '';
                    document.getElementById('progDone').textContent = '同步已完成';
                    document.getElementById('progDone').style.color = 'var(--accent-green)';
                    clearInterval(self._progressTimer);
                } else if (p.status === 'FAILED') {
                    document.getElementById('progDone').style.display = '';
                    var failMsg = '同步失败';
                    var fc = p.failRows || 0;
                    if (fc > 0) failMsg += '（成功 ' + (p.successRows || 0).toLocaleString() + ' 行，失败 ' + fc + ' 批）';
                    document.getElementById('progDone').textContent = failMsg;
                    document.getElementById('progDone').style.color = 'var(--accent-red)';
                    clearInterval(self._progressTimer);
                } else if (p.status === 'NOT_FOUND') {
                    document.getElementById('progDone').style.display = '';
                    document.getElementById('progDone').textContent = '任务已结束';
                    document.getElementById('progDone').style.color = 'var(--text-muted)';
                    clearInterval(self._progressTimer);
                }
            }).catch(function() {
                // 任务已停止/不存在, 关闭轮询
                clearInterval(self._progressTimer);
            });
        }
        this._progressTimer = setInterval(update, 2000);
        update();

        // 监听body的子节点变化(弹窗被remove时触发), 停止定时器
        var observer = new MutationObserver(function() {
            if (!document.body.contains(m)) {
                clearInterval(self._progressTimer);
                self._progressTimer = null;
                observer.disconnect();
            }
        });
        observer.observe(document.body, { childList: true });
    },

    async _batchQuerySourceMax() {
        const rows = document.querySelectorAll('#syncTaskBody tr[data-task-id]');
        if (!rows.length) return;
        App.showToast('正在查询全部任务...', 'info');
        const map = {};
        const taskIds = [];
        rows.forEach(row => {
            const tid = row.dataset.taskId;
            taskIds.push(tid);
            map[tid] = row;
            row.querySelector('.task-source-max').textContent = '查询中...';
        });
        try {
            const results = await Api.batchSourceMax(taskIds);
            taskIds.forEach(id => {
                const r = results[id];
                const cell = map[id].querySelector('.task-source-max');
                cell.textContent = (r && r.sourceMaxValue) || '-';
            });
            App.showToast(`查询完成: ${taskIds.length}个任务`, 'success');
        } catch(e) {
            taskIds.forEach(id => { map[id].querySelector('.task-source-max').textContent = '失败'; });
        }
    },

    _onTaskSearch(val) {
        this._taskSearchKeyword = val.trim().toLowerCase();
        this._renderTaskList();
    },

    _getTaskGroup(task) { return Utils.getTaskGroup(task); },

    _buildGroupMap() { return Utils.buildGroupMap(this._taskList); },

    _renderTaskList() {
        const folderView = document.getElementById('groupFolderView');
        const tableView = document.getElementById('groupTaskTableView');
        const titleEl = document.getElementById('taskListTitle');
        const backEl = document.getElementById('taskListBack');
        if (!folderView || !tableView) return;

        const { groups, noGroup } = this._buildGroupMap();

        if (this._taskSearchKeyword) {
            folderView.style.display = 'none';
            tableView.style.display = '';
            titleEl.textContent = '搜索结果';
            backEl.style.display = '';
            backEl.innerHTML = Icons.x() + ' 清除搜索';
            backEl.onclick = () => { this._taskSearchKeyword = ''; this._currentGroupName = null; document.getElementById('taskSearchInput').value = ''; this._renderTaskList(); };
            const kw = this._taskSearchKeyword;
            this._renderFlatTaskList(this._taskList.filter(t => {
                const src = (t.src_table || '').toLowerCase();
                const tgt = (t.tgt_table || '').toLowerCase();
                const name = (t.name || '').toLowerCase();
                const group = (this._getTaskGroup(t) || '').toLowerCase();
                return src.includes(kw) || tgt.includes(kw) || name.includes(kw) || group.includes(kw);
            }));
            return;
        }

        if (this._currentGroupName !== null) {
            folderView.style.display = 'none';
            tableView.style.display = '';
            titleEl.textContent = this._currentGroupName === '__nogroup__' ? '未分组任务' : this._currentGroupName;
            backEl.style.display = '';
            backEl.textContent = '← 返回分组列表';
            backEl.onclick = () => { this._backToGroupList(); };
            const tasks = this._currentGroupName === '__nogroup__' ? noGroup : (groups.get(this._currentGroupName) || []);
            this._renderFlatTaskList(tasks);
            return;
        }

        folderView.style.display = '';
        tableView.style.display = 'none';
        titleEl.textContent = '已配置的同步任务';
        backEl.style.display = 'none';
        this._renderGroupFolderView(groups, noGroup);
    },

    _renderGroupFolderView(groups, noGroup) {
        const folderView = document.getElementById('groupFolderView');
        if (!folderView) return;
        folderView.innerHTML = '';

        if (!this._taskList.length) {
            folderView.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-muted)">暂无同步任务</div>';
            return;
        }

        const cardTpl = document.getElementById('tpl-folder-card');

        if (groups.size > 0) {
            const grid = document.createElement('div');
            grid.className = 'group-folder-grid';
            groups.forEach((tasks, groupName) => {
                const card = cardTpl.content.firstElementChild.cloneNode(true);
                card.onclick = () => this._enterGroup(groupName);
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
            const section = document.createElement('div');
            section.className = 'group-ungrouped-section';
            let html = '<div class="group-ungrouped-header">';
            if (groups.size > 0) {
                html += `<span style="font-size:16px;">${Icons.fileText()}</span><span>未分组 (${noGroup.length} 个任务)</span>
                    <span class="detail-link" style="margin-left:auto;" onclick="Config._enterGroup('__nogroup__')">查看全部 →</span>`;
            } else {
                html += `<span style="font-size:16px;">${Icons.fileText()}</span><span>所有任务 (${noGroup.length} 个)</span>`;
            }
            html += '</div>';
            section.innerHTML = html;
            noGroup.slice(0, groups.size > 0 ? 3 : 999).forEach(task => {
                section.appendChild(this._buildCompactTaskRow(task));
            });
            if (groups.size > 0 && noGroup.length > 3) {
                const more = document.createElement('div');
                more.style.cssText = 'text-align:center;padding:8px;font-size:12px;color:var(--text-muted);';
                more.innerHTML = `还有 ${noGroup.length - 3} 个任务，<span class="detail-link" onclick="Config._enterGroup('__nogroup__')">查看全部</span>`;
                section.appendChild(more);
            }
            folderView.appendChild(section);
        } else if (groups.size === 0 && noGroup.length === 0 && !folderView.children.length) {
            folderView.innerHTML = '<div style="text-align:center;padding:30px;color:var(--text-muted)">暂无同步任务</div>';
        }
    },

    _buildCompactTaskRow(task) {
        const div = document.createElement('div');
        div.style.cssText = 'display:flex;align-items:center;gap:10px;padding:6px 0;font-size:13px;border-bottom:1px solid rgba(48,54,61,.4);';
        const srcLabel = Utils.buildTableLabel(task.source_db_name, task.src_table);
        const tgtLabel = Utils.buildTableLabel(task.target_db_name, task.tgt_table);
        const isSql = !!task.src_sql;
        const statusInfo = Utils.getStatusInfo(task.status);
        div.innerHTML = `<span class="db-badge src" style="flex-shrink:0;">${Utils.esc(srcLabel)}</span>${isSql ? '<span style="font-size:10px;color:var(--accent-purple);background:rgba(163,113,247,.12);padding:1px 6px;border-radius:3px;">SQL</span>' : ''}<span style="color:var(--text-muted);">→</span><span class="db-badge tgt" style="flex-shrink:0;">${Utils.esc(tgtLabel)}</span><span style="margin-left:auto;flex-shrink:0;">${statusInfo.html}</span>`;
        return div;
    },

    _enterGroup(groupName) {
        this._currentGroupName = groupName;
        this._renderTaskList();
    },

    _backToGroupList() {
        this._currentGroupName = null;
        this._taskSearchKeyword = '';
        const si = document.getElementById('taskSearchInput');
        if (si) si.value = '';
        this._renderTaskList();
    },

    _renderFlatTaskList(tasks) {
        const tbody = document.getElementById('syncTaskBody');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!tasks.length) {
            tbody.innerHTML = '<tr><td colspan="10" style="text-align:center;padding:30px;color:var(--text-muted)">暂无匹配的任务</td></tr>';
            return;
        }
        const tpl = document.getElementById('tpl-task-row');
        tasks.forEach(task => {
            const row = this._buildTaskRow(task, tpl);
            tbody.appendChild(row);
        });
    },

    _buildTaskRow(task, tpl) {
        const row = tpl.content.firstElementChild.cloneNode(true);
        const strategyLabel = STRATEGY_LABELS[task.strategy] || { text: task.strategy || '增量', class: '' };
        const srcLabel = Utils.buildTableLabel(task.source_db_name, task.src_table);
        const tgtLabel = Utils.buildTableLabel(task.target_db_name, task.tgt_table);
        const isSql = !!task.src_sql;
        const tid = Utils.esc(task.id);

        row.querySelector('.task-src-badge').textContent = srcLabel;
        if (isSql) row.querySelector('.task-sql-badge').style.display = '';
        row.querySelector('.task-tgt-badge').textContent = tgtLabel;
        const stratEl = row.querySelector('.task-strategy');
        stratEl.textContent = strategyLabel.text;
        stratEl.className = 'sync-strategy ' + strategyLabel.class;
        // 读取模式标记
        const tmEl = row.querySelector('.task-fetch-mode');
        if (tmEl) {
            const fmLabel = FETCH_MODE_LABELS[task.fetchMode || task.fetch_mode] || FETCH_MODE_LABELS['parallel'];
            tmEl.textContent = fmLabel.text;
            tmEl.className = 'fetch-mode-badge ' + fmLabel.class;
        }
        row.querySelector('.task-sched-name').innerHTML = task.schedule_name ? '<span class="sched-tag">' + Utils.esc(task.schedule_name) + '</span>' : '-';
        row.dataset.taskId = tid;
        row.querySelector('.task-incr-field').innerHTML = task.increment_field ? '<code class="incr-field-code">' + Utils.esc(task.increment_field) + '</code>' : '-';
        row.querySelector('.task-last-marker').textContent = task.last_marker_value || '-';
        row.querySelector('.btn-source-max').onclick = function() { Config._querySourceMax(tid, row); };
        row.querySelector('.btn-edit-marker').onclick = function() { Config._editMarker(tid, row); };
        row.querySelector('.btn-edit-marker').style.display = '';
        if (task.status === 'running') {
            row.querySelector('.btn-progress').style.display = '';
            row.querySelector('.btn-progress').onclick = function() { Config._showProgress(tid); };
        }
        let fm = task.field_mappings;
        if (typeof fm === 'string') try { fm = JSON.parse(fm); } catch(e) { fm = []; }
        row.querySelector('.task-mapping-count').textContent = Array.isArray(fm) ? fm.length : 0;
        row.querySelector('.task-status-td').innerHTML = Utils.getStatusInfo(task.status).html;

        row.querySelector('.btn-edit-task').onclick = function() { Config._editTask(tid); };
        row.querySelector('.btn-del-task').onclick = function() { Config._deleteTask(tid); };
        row.querySelector('.btn-group-task').onclick = function() { Config._showGroupModal(tid); };

        if (task.status === 'running') {
            row.querySelector('.btn-pause-task').style.display = '';
            row.querySelector('.btn-pause-task').onclick = function() { Config._pauseTask(tid); };
            row.querySelector('.btn-stop-task').style.display = '';
            row.querySelector('.btn-stop-task').onclick = function() { Config._stopTask(tid); };
        } else if (task.status === 'paused') {
            row.querySelector('.btn-start-task').style.display = '';
            row.querySelector('.btn-start-task').onclick = function() { Config._startTask(tid); };
            row.querySelector('.btn-stop-task').style.display = '';
            row.querySelector('.btn-stop-task').onclick = function() { Config._stopTask(tid); };
        } else {
            row.querySelector('.btn-start-task').style.display = '';
            row.querySelector('.btn-start-task').onclick = function() { Config._startTask(tid); };
        }
        return row;
    },

    _renderTaskListError(msg) {
        const tbody = document.getElementById('syncTaskBody');
        if (tbody) tbody.innerHTML = `<tr><td colspan="10" style="text-align:center;padding:30px;color:var(--accent-red)">${Utils.esc(msg)}</td></tr>`;
    },

    // ---- 分组模态框 ----

    _showGroupModal(taskId) {
        this._refreshGroupSelect('', true);
        document.getElementById('groupModalTaskId').value = taskId;
        const task = this._taskList.find(t => t.id === taskId);
        document.getElementById('groupModalInfo').textContent = task
            ? `任务: ${Utils.buildTableLabel(task.source_db_name, task.src_table)} → ${Utils.buildTableLabel(task.target_db_name, task.tgt_table)}`
            : '';
        document.getElementById('groupModalSelect').value = task ? (task.task_group || '') : '';
        document.getElementById('groupModalInput').value = '';
        document.getElementById('groupEditModal').style.display = 'flex';
    },

    _closeGroupModal() {
        document.getElementById('groupEditModal').style.display = 'none';
    },

    _onGroupModalSelectChange() {
        const val = document.getElementById('groupModalSelect').value;
        document.getElementById('groupModalInput').style.display = val === '__new__' ? '' : 'none';
    },

    _saveGroupModal() {
        const taskId = document.getElementById('groupModalTaskId').value;
        let groupName = document.getElementById('groupModalSelect').value;
        if (groupName === '__new__') {
            groupName = document.getElementById('groupModalInput').value.trim();
        }
        this._closeGroupModal();
        this._saveTaskGroupToDb(taskId, groupName || '');
    },

    async _saveTaskGroupToDb(taskId, groupName) {
        const task = this._taskList.find(t => t.id === taskId);
        if (!task) return;
        try {
            let fm = task.field_mappings;
            if (typeof fm === 'string') try { fm = JSON.parse(fm); } catch(e) { fm = []; }
            await Api.saveSyncTask({
                id: taskId,
                source_db_id: task.source_db_id,
                target_db_id: task.target_db_id,
                src_table: task.src_table,
                src_sql: task.src_sql || null,
                tgt_table: task.tgt_table,
                strategy: task.strategy,
                schedule_id: task.schedule_id,
                increment_field: task.increment_field,
                field_mappings: Array.isArray(fm) ? fm : [],
                task_group: groupName || null,
                no_update: !!task.no_update
            });
        } catch (e) { console.warn('保存分组到数据库失败:', e); }
        this._currentGroupName = null;
        this._loadSyncTasks();
    },

    // ---- 编辑/删除/启动/暂停 ----

    async _editTask(taskId) {
        const task = this._taskList.find(t => t.id === taskId);
        if (!task) { console.warn('[DEBUG] _editTask: task not found for id=', taskId); return; }
        console.log('[DEBUG] _editTask: task found', {id: task.id, src_table: task.src_table, tgt_table: task.tgt_table, source_db_id: task.source_db_id, target_db_id: task.target_db_id, src_sql: task.src_sql, field_mappings: task.field_mappings});
        this._editingTaskId = taskId;
        // 重置字段状态，避免上一个编辑任务的残留数据
        this._sourceFields = [];
        this._targetFields = [];
        this._fieldMappings = [];
        this._sourceMode = task.src_sql ? 'sql' : 'table';
        this._editingSrcSql = task.src_sql || null;

        let fullTask = task;
        try {
            const detail = await Api.getSyncTask(taskId);
            if (detail) fullTask = { ...task, ...detail };
        } catch(e) { console.warn('获取任务详情失败，使用列表数据:', e); }

        if (fullTask.src_sql) {
            this._sourceMode = 'sql';
            this._editingSrcSql = fullTask.src_sql;
        }

        try {
            const srcSel = document.getElementById('sourceDbSelect');
            const tgtSel = document.getElementById('targetDbSelect');
            await this.refreshDbSelects();

            // 如果缺少 source_db_id，尝试通过 source_db_name 查找
            if (!fullTask.source_db_id && fullTask.source_db_name) {
                const found = Store.dbConnections.find(d => d.name === fullTask.source_db_name);
                if (found) fullTask.source_db_id = found.id;
            }
            if (!fullTask.target_db_id && fullTask.target_db_name) {
                const found = Store.dbConnections.find(d => d.name === fullTask.target_db_name);
                if (found) fullTask.target_db_id = found.id;
            }

            if (fullTask.source_db_id) {
                srcSel.value = fullTask.source_db_id;
                this.onSourceDbChange();
            }
            if (fullTask.target_db_id) {
                tgtSel.value = fullTask.target_db_id;
                this.onTargetDbChange();
            }

            if (this._sourceMode === 'sql') {
                this._setComboboxValue('srcTable', fullTask.src_table || '');
                this._hideDropdown('srcTable');
                const sqlNameInput = document.getElementById('srcSqlName');
                if (sqlNameInput) sqlNameInput.value = fullTask.src_table || '';
            } else {
                if (fullTask.source_db_id) {
                    await this._loadTablesToCombobox(fullTask.source_db_id, 'srcTable', true);
                }
                this._setComboboxValue('srcTable', fullTask.src_table || '');
                this._hideDropdown('srcTable');
            }

            const tgtInput = document.getElementById('tgtTableInput');
            if (tgtInput) { tgtInput.disabled = false; tgtInput.placeholder = '输入或选择目标表...'; }
            if (fullTask.target_db_id) {
                await this._loadTablesToCombobox(fullTask.target_db_id, 'tgtTable', false);
            }
            this._setComboboxValue('tgtTable', fullTask.tgt_table || '');
            this._hideDropdown('tgtTable');

            let rawMappings = fullTask.field_mappings;
            if (typeof rawMappings === 'string') {
                try { rawMappings = JSON.parse(rawMappings); } catch(e) { rawMappings = []; }
            }
            const mappingArray = Array.isArray(rawMappings)
                ? rawMappings
                : (rawMappings && typeof rawMappings === 'object' ? Object.values(rawMappings) : []);
            this._fieldMappings = mappingArray.length > 0
                ? mappingArray.map(m => ({ srcField: m.srcField || '', tgtField: m.tgtField || '', isPk: !!m.isPk }))
                : [];

            this._currentIncrField = fullTask.increment_field || '';

            if (this._sourceMode === 'sql' && fullTask.src_sql) {
                try {
                    const columns = await Api.getSqlColumns(fullTask.source_db_id, fullTask.src_sql);
                    this._sourceFields = this._normalizeFields(columns);
                } catch(e) {
                    console.warn('编辑时解析SQL字段失败，从映射恢复:', e);
                    this._sourceFields = mappingArray.map(m => ({ name: m.srcField, type: '' })).filter(m => m.name);
                }
                try {
                    const tgtRes = await Api.getTableColumns(fullTask.target_db_id, fullTask.tgt_table);
                    this._targetFields = this._normalizeFields(tgtRes);
                } catch(e) {
                    console.warn('编辑时加载目标表字段失败，从映射恢复:', e);
                    this._targetFields = mappingArray.map(m => ({ name: m.tgtField, type: '' })).filter(m => m.name);
                }
                const incrSel = document.getElementById('incrFieldSelect');
                if (incrSel) {
                    this._populateIncrFieldSelect(incrSel, this._sourceFields);
                    if (this._currentIncrField) incrSel.value = this._currentIncrField;
                }
                this._restoreFieldMappings(fullTask.src_table);
                this._renderFieldMappingArea();
            } else {
                await this._onBothTablesSelected();
            }

            const strategySel = document.getElementById('syncStrategySelect');
            if (strategySel) {
                strategySel.value = fullTask.strategy || 'incremental';
                this._onStrategyChange();
            }

            const noUpdateCheck = document.getElementById('noUpdateCheck');
            if (noUpdateCheck) noUpdateCheck.checked = !!fullTask.no_update;

            const fetchModeSel = document.getElementById('fetchModeSelect');
            if (fetchModeSel) {
                fetchModeSel.value = fullTask.fetchMode || fullTask.fetch_mode || 'parallel';
            }

            const incrSel2 = document.getElementById('incrFieldSelect');
            if (incrSel2 && fullTask.increment_field) {
                incrSel2.value = fullTask.increment_field;
            }
            this._currentIncrValue = fullTask.last_marker_value || null;

            await this._refreshScheduleSelect(fullTask.schedule_id || '');

            const taskGroup = fullTask.task_group || '';
            this._refreshGroupSelect(taskGroup);
            if (!taskGroup) {
                const groupInput = document.getElementById('taskGroupInput');
                if (groupInput) groupInput.value = '';
            }

            console.log('[DEBUG] _editTask final check:', {fieldMappingsLen: this._fieldMappings.length, sourceFieldsLen: this._sourceFields.length, targetFieldsLen: this._targetFields.length, sourceFields: this._sourceFields.slice(0,3), targetFields: this._targetFields.slice(0,3)});
            if (this._fieldMappings.length > 0 && !this._sourceFields.length) {
                console.warn('[DEBUG] _editTask: _sourceFields empty, extracting from mappings');
                this._sourceFields = this._fieldMappings.map(m => ({ name: m.srcField, type: '' })).filter(m => m.name);
            }
            if (this._fieldMappings.length > 0 && !this._targetFields.length) {
                console.warn('[DEBUG] _editTask: _targetFields empty, extracting from mappings');
                this._targetFields = this._fieldMappings.map(m => ({ name: m.tgtField, type: '' })).filter(m => m.name);
            }
            // Note: _renderFieldMappingArea already called in SQL/table mode block above;
            // only call here as fallback if fields were just extracted from mappings
            if (this._fieldMappings.length > 0 && this._sourceFields.length > 0 && this._targetFields.length > 0
                && !document.getElementById('fieldMappingBody')) {
                console.log('[DEBUG] _editTask: fallback _renderFieldMappingArea');
                this._renderFieldMappingArea();
            }
        } catch (err) {
            console.error('编辑任务回显失败:', err);
            App.showToast('加载任务配置失败: ' + (err.message || ''), 'error');
        }

        document.getElementById('page-config')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        App.showToast('已加载任务配置到上方，修改后点击「更新配置」更新');
    },

    _cancelEdit() {
        this._editingTaskId = null;
        this._sourceMode = 'table';
        this._sqlEditor = null;
        this._editingSrcSql = null;
        this._sourceFields = [];
        this._targetFields = [];
        this._fieldMappings = [];
        const srcSel = document.getElementById('sourceDbSelect');
        const tgtSel = document.getElementById('targetDbSelect');
        if (srcSel) srcSel.value = '';
        if (tgtSel) tgtSel.value = '';
        document.getElementById('sourceDbFormArea').innerHTML =
            '<p style="font-size:13px;color:var(--text-muted);margin-bottom:12px;">请在「数据库管理」页面添加数据源库，然后在此处选择。</p>';
        document.getElementById('targetDbFormArea').innerHTML =
            '<p style="font-size:13px;color:var(--text-muted);margin-bottom:12px;">请在「数据库管理」页面添加目标库，然后在此处选择。</p>';
        App.showToast('已取消编辑');
    },

    async _deleteTask(taskId) {
        if (!confirm('确定要删除该同步任务吗？')) return;
        try {
            await Api.deleteSyncTask(taskId);
            App.showToast('任务已删除');
            this._loadSyncTasks();
        } catch (e) {}
    },

    async _startTask(taskId) {
        try {
            await Api.startSyncTask(taskId);
            App.showToast('任务已启动');
            this._loadSyncTasks();
        } catch (e) {}
    },

    async _pauseTask(taskId) {
        try {
            await Api.pauseSyncTask(taskId);
            App.showToast('任务已暂停');
            this._loadSyncTasks();
        } catch (e) {}
    },

    async _stopTask(taskId) {
        if (!confirm('确定要停止该任务吗？')) return;
        try {
            await Api.stopSyncTask(taskId);
            App.showToast('任务已停止');
            this._loadSyncTasks();
        } catch (e) {}
    },

    // ---- DB选择 ----

    async refreshDbSelects() {
        const srcSel = document.getElementById('sourceDbSelect');
        const tgtSel = document.getElementById('targetDbSelect');
        if (!srcSel || !tgtSel) return;
        const currentSrc = srcSel.value;
        const currentTgt = tgtSel.value;
        try {
            const databases = await Api.getDatabases();
            if (databases && databases.length > 0) {
                databases.forEach(d => { d.id = String(d.id); });
                Store.dbConnections = databases;
            }
        } catch (error) {
            console.warn('API加载数据库列表失败，使用本地缓存:', error.message);
        }

        const databases = Store.dbConnections;
        if (databases && databases.length > 0) {
            const options = databases.map(db => {
                const info = DB_TYPE_INFO[db.type] || DB_TYPE_INFO.mysql;
                return `<option value="${db.id}">${Utils.esc(db.name)} (${info.label})</option>`;
            }).join('');
            srcSel.innerHTML = '<option value="">选择数据源库...</option>' + options;
            tgtSel.innerHTML = '<option value="">选择目标库...</option>' + options;
            if (currentSrc && databases.find(d => d.id === currentSrc)) srcSel.value = currentSrc;
            if (currentTgt && databases.find(d => d.id === currentTgt)) tgtSel.value = currentTgt;
        }
    },

    onSourceDbChange() {
        const sel = document.getElementById('sourceDbSelect').value;
        const area = document.getElementById('sourceDbFormArea');
        const icon = document.getElementById('srcPanelIcon');
        const label = document.getElementById('srcDbTypeLabel');
        const status = document.getElementById('srcConnStatus');
        if (!sel || !area) return;
        const db = Store.dbConnections.find(d => d.id === sel);
        if (!db) return;
        const info = DB_TYPE_INFO[db.type] || DB_TYPE_INFO.mysql;
        icon.innerHTML = info.icon;
        label.textContent = `(${info.label})`;
        status.className = `conn-status ${db.connected ? 'connected' : 'disconnected'}`;
        status.innerHTML = `<div class="conn-dot ${db.connected ? 'on' : 'off'}"></div>${db.connected ? '已连接' : '未连接'}`;
        this._renderSourceTableSelector(sel);
    },

    _renderSourceTableSelector(dbId) {
        const area = document.getElementById('sourceDbFormArea');
        const isSql = this._sourceMode === 'sql';
        const tplId = isSql ? 'tpl-source-sql-mode' : 'tpl-source-table-mode';
        const tpl = document.getElementById(tplId);
        area.innerHTML = '';
        area.appendChild(tpl.content.cloneNode(true));

        // 初始化 combobox
        this._bindCombobox('srcTable', '输入或选择源表...');
        this._bindCombobox('tgtTable', '输入或选择目标表...');

        // SQL 模式初始化编辑器
        if (isSql) {
            setTimeout(() => this._initSqlEditor(), 50);
            const tgtInput = document.getElementById('tgtTableInput');
            if (tgtInput) { tgtInput.disabled = false; tgtInput.placeholder = '输入或选择目标表...'; }
            if (document.getElementById('targetDbSelect')?.value) {
                this._loadTablesToCombobox(document.getElementById('targetDbSelect').value, 'tgtTable', false);
            }
            // 回填 SQL 编辑器中的旧内容
            if (this._editingSrcSql) {
                setTimeout(() => {
                    if (this._sqlEditor && this._editingSrcSql) {
                        this._sqlEditor.setValue(this._editingSrcSql);
                        this._editingSrcSql = null;
                    }
                }, 150);
            }
        } else {
            this._loadTablesToCombobox(dbId, 'srcTable', true);
        }

        // 加载目标表
        const tgtDbId = document.getElementById('targetDbSelect')?.value;
        if (tgtDbId) {
            const tgtInput = document.getElementById('tgtTableInput');
            if (tgtInput) { tgtInput.disabled = false; tgtInput.placeholder = '输入或选择目标表...'; }
            this._loadTablesToCombobox(tgtDbId, 'tgtTable', false);
        }

        // 刷新调度和分组下拉
        this._refreshScheduleSelect();
        this._refreshGroupSelect();
    },

    onTargetDbChange() {
        const sel = document.getElementById('targetDbSelect').value;
        const area = document.getElementById('targetDbFormArea');
        const icon = document.getElementById('tgtPanelIcon');
        const label = document.getElementById('tgtDbTypeLabel');
        const status = document.getElementById('tgtConnStatus');
        if (!sel || !area) return;
        const db = Store.dbConnections.find(d => d.id === sel);
        if (!db) return;
        const info = DB_TYPE_INFO[db.type] || DB_TYPE_INFO.mysql;
        icon.innerHTML = info.icon;
        label.textContent = `(${info.label})`;
        status.className = `conn-status ${db.connected ? 'connected' : 'disconnected'}`;
        status.innerHTML = `<div class="conn-dot ${db.connected ? 'on' : 'off'}"></div>${db.connected ? '已连接' : '未连接'}`;
        area.innerHTML = `<div style="text-align:center;padding:30px 0;color:var(--text-muted);">">
            <p style="margin-bottom:8px;">请在左侧选择「源表」和「目标表」</p>
            <p style="font-size:11px;">选好后将自动加载字段映射配置</p></div>`;
        // 如果源表选择了, 刷新源区的目标表选单
        if (document.getElementById('sourceDbSelect')?.value) {
            this._loadTablesToCombobox(sel, 'tgtTable', false);
            const tgtInput = document.getElementById('tgtTableInput');
            if (tgtInput) { tgtInput.disabled = false; tgtInput.placeholder = '输入或选择目标表...'; }
        }
    },

    // ---- 模式切换 ----

    _onSourceModeChange(mode) {
        this._sourceMode = mode;
        const dbId = document.getElementById('sourceDbSelect')?.value;
        if (!dbId) return;
        this._renderSourceTableSelector(dbId);
    },

    // ---- SQL 编辑器 ----

    _initSqlEditor() {
        const textarea = document.getElementById('srcSqlEditor');
        if (!textarea || typeof CodeMirror === 'undefined') return;

        let prevSql = '';
        if (this._sqlEditor) {
            const wrapper = this._sqlEditor.getWrapperElement();
            if (wrapper && document.body.contains(wrapper)) {
                this._sqlEditor.refresh();
                return;
            }
            prevSql = this._sqlEditor.getValue() || '';
            this._sqlEditor = null;
        }

        const dbId = document.getElementById('sourceDbSelect')?.value;
        const db = dbId ? Store.dbConnections.find(d => d.id === dbId) : null;
        const dbType = db?.type || 'mysql';

        this._sqlEditor = CodeMirror.fromTextArea(textarea, {
            mode: 'text/x-' + dbType,
            theme: 'eclipse',
            lineNumbers: true,
            matchBrackets: true,
            indentWithTabs: true,
            smartIndent: true,
            tabSize: 2,
            lineWrapping: true,
            viewportMargin: Infinity,
            extraKeys: {
                'Ctrl-Space': 'autocomplete',
                'Ctrl-Enter': () => this._parseSqlColumns()
            }
        });

        this._sqlEditor.on('inputRead', (cm, change) => {
            if (change.text[0] && /\w/.test(change.text[0])) {
                CodeMirror.commands.autocomplete(cm, null, { completeSingle: false });
            }
        });

        if (this._editingSrcSql) {
            this._sqlEditor.setValue(this._editingSrcSql);
            this._editingSrcSql = null;
        } else if (prevSql && prevSql.trim()) {
            this._sqlEditor.setValue(prevSql);
        } else {
            this._sqlEditor.setValue('SELECT \n  \nFROM \nWHERE 1=1');
        }
        setTimeout(() => this._sqlEditor?.refresh(), 100);
    },

    async _parseSqlColumns() {
        const sql = this._sqlEditor?.getValue?.()?.trim();
        const dbId = document.getElementById('sourceDbSelect')?.value;
        const infoEl = document.getElementById('sqlParsedInfo');
        const nameInput = document.getElementById('srcSqlName');

        if (!sql) { App.showToast('请先输入SQL语句', 'error'); return; }
        if (!dbId) { App.showToast('请先选择源数据库', 'error'); return; }
        if (!nameInput?.value?.trim()) { App.showToast('请先为自定义SQL命名', 'error'); nameInput?.focus(); return; }

        if (infoEl) { infoEl.style.display = 'flex'; infoEl.className = 'sql-parsed-info'; infoEl.innerHTML = Icons.loader() + ' 正在解析SQL字段...'; }

        try {
            const columns = await Api.getSqlColumns(dbId, sql);
            this._sourceFields = this._normalizeFields(columns);
            if (infoEl) {
                if (this._sourceFields.length > 0) {
                    infoEl.className = 'sql-parsed-info';
                    infoEl.innerHTML = Icons.check() + ` 成功解析 ${this._sourceFields.length} 个字段`;
                } else {
                    infoEl.className = 'sql-parsed-info error';
                    infoEl.innerHTML = Icons.alert() + ' 未解析到任何字段，请检查SQL语句';
                }
            }
            const incrSel = document.getElementById('incrFieldSelect');
            if (incrSel) this._populateIncrFieldSelect(incrSel, this._sourceFields);
            const tgtTable = this._getComboboxValue('tgtTable');
            if (tgtTable) {
                this._onBothTablesSelected();
            }
            App.showToast(`解析成功，发现 ${this._sourceFields.length} 个字段`);
        } catch (error) {
            if (infoEl) {
                infoEl.style.display = 'flex';
                infoEl.className = 'sql-parsed-info error';
                infoEl.innerHTML = Icons.x() + ' 解析失败: ' + Utils.esc(error.message || '请检查SQL语法');
            }
        }
    },

    _formatSql() {
        if (!this._sqlEditor) return;
        try {
            const sql = this._sqlEditor.getValue();
            if (typeof sqlFormatter !== 'undefined') {
                this._sqlEditor.setValue(sqlFormatter.format(sql));
            }
        } catch(e) {}
    },

    _showResultModal(title, bodyHtml) {
        const id = 'sqlTestResultModal';
        let m = document.getElementById(id);
        if (!m) {
            m = document.createElement('div');
            m.id = id;
            m.className = 'modal-overlay';
            m.innerHTML = `<div class="modal-box" style="max-width:900px;max-height:80vh;"><div class="modal-header"><span class="modal-title"></span><button class="modal-close" onclick="document.getElementById('${id}').classList.remove('show')">${Icons.x()}</button></div><div class="modal-body"></div></div>`;
            document.body.appendChild(m);
        }
        m.querySelector('.modal-title').textContent = title;
        m.querySelector('.modal-body').innerHTML = bodyHtml;
        m.classList.add('show');
        m.onclick = e => { if (e.target === m) m.classList.remove('show'); };
    },

    _buildParams(action) {
        const srcDbId = document.getElementById('sourceDbSelect')?.value;
        const sql = this._sqlEditor?.getValue()?.trim();
        if (!srcDbId || !sql) { return null; }
        const incrSel = document.getElementById('incrFieldSelect');
        const timeVal = document.getElementById('sqlParamTimeValue')?.value?.trim();
        const limitVal = parseInt(document.getElementById('sqlParamLimit')?.value) || 1;
        const data = {
            sourceSql: sql,
            markerField: incrSel?.value || null,
            lastMarkerValue: timeVal || null,
            sourceWhere: null,
            limit: action === 'test' ? limitVal : 1
        };
        return { srcDbId, data };
    },

    _showSqlParamsDialog(action) {
        const srcDbId = document.getElementById('sourceDbSelect')?.value;
        const sql = this._sqlEditor?.getValue()?.trim();
        if (!srcDbId || !sql) { App.showToast('请先选择源数据库并输入SQL', 'error'); return; }

        // 默认时间: 已存的上次同步时间 > 当前时间-1小时(本地时间)
        let defTime = this._currentIncrValue;
        if (!defTime) {
            defTime = this._fmtLocalTime(-1);
        }
        const title = action === 'test' ? '测试执行' : 'SQL预览';

        const id = 'sqlParamsDialog';
        let m = document.getElementById(id);
        if (m) m.remove();
        m = document.createElement('div');
        m.id = id;
        m.className = 'modal-overlay show';
        m.innerHTML = `
        <div class="modal-box" style="max-width:480px;">
            <div class="modal-header"><span class="modal-title">${title}</span><button class="modal-close" type="button" onclick="document.getElementById('${id}').remove()">${Icons.x()}</button></div>
            <div class="modal-body">
                <div class="form-group">
                    <label class="form-label">${Icons.clock()} 时间偏移 (增量标记值)</label>
                    <input class="form-input" id="sqlParamTimeValue" placeholder="如: 2026-06-12 10:00:00" value="${Utils.esc(defTime)}" style="width:100%;box-sizing:border-box;">
                    <div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap;">
                        <button class="btn btn-sm" type="button" onclick="Config._setTimePreset('1h')" title="当前时间-1小时">最近1小时</button>
                        <button class="btn btn-sm" type="button" onclick="Config._setTimePreset('24h')" title="当前时间-24小时">最近24小时</button>
                        <button class="btn btn-sm" type="button" onclick="Config._setTimePreset('7d')" title="当前时间-7天">最近7天</button>
                        <button class="btn btn-sm" type="button" onclick="Config._setTimePreset('none')" title="不加时间条件">不限时间</button>
                    </div>
                </div>
                ${action === 'test' ? `
                <div class="form-group">
                    <label class="form-label">${Icons.barChart()} 返回行数</label>
                    <input class="form-input" id="sqlParamLimit" type="number" value="1" min="1" max="10000" style="width:100px;box-sizing:border-box;">
                </div>` : ''}
                <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px;">
                    <button class="btn" type="button" onclick="document.getElementById('${id}').remove()">取消</button>
                    <button class="btn btn-primary" type="button" id="sqlParamsConfirm">确定</button>
                </div>
            </div>
        </div>`;
        document.body.appendChild(m);
        m.onclick = e => { if (e.target === m) m.remove(); };

        const self = this;
        document.getElementById('sqlParamsConfirm').onclick = async function() {
            const params = self._buildParams(action);
            m.remove();
            if (!params) return;
            if (action === 'test') {
                await self._doTestSql(params.srcDbId, params.data);
            } else {
                await self._doPreviewSql(params.srcDbId, params.data);
            }
        };
    },

    _fmtLocalTime(offsetHours) {
        const d = new Date();
        if (offsetHours) d.setHours(d.getHours() + offsetHours);
        const pad = n => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    },

    _setTimePreset(offset) {
        if (offset === 'none') {
            document.getElementById('sqlParamTimeValue').value = '';
            return;
        }
        let h = 0;
        if (offset === '1h') h = -1;
        else if (offset === '24h') h = -24;
        else if (offset === '7d') h = -168;
        document.getElementById('sqlParamTimeValue').value = this._fmtLocalTime(h);
    },

    async _previewSql() { this._showSqlParamsDialog('preview'); },

    async _testSql() { this._showSqlParamsDialog('test'); },

    async _doPreviewSql(srcDbId, data) {
        try {
            const result = await Api.sqlBuild(srcDbId, data);
            this._showResultModal('SQL预览', `<pre style="background:var(--bg-secondary);padding:16px;border-radius:6px;overflow:auto;font-size:13px;white-space:pre-wrap;word-break:break-all;max-height:60vh;margin:0;">${Utils.esc(result.builtSql)}</pre>`);
        } catch(e) {}
    },

    async _doTestSql(srcDbId, data) {
        try {
            const result = await Api.sqlTest(srcDbId, data);
            let html = `<div style="margin-bottom:8px;font-size:12px;color:var(--text-muted);">总行数: ${result.totalRows} | 耗时: ${result.execTimeMs}ms | 返回: ${(result.rows||[]).length}行</div>`;
            if (result.columns && result.columns.length) {
                html += '<div style="overflow:auto;max-height:55vh;"><table style="width:100%;border-collapse:collapse;font-size:13px;"><thead><tr>';
                result.columns.forEach(c => html += `<th style="position:sticky;top:0;background:var(--bg-primary);padding:8px 12px;border-bottom:2px solid var(--border);text-align:left;white-space:nowrap;">${Utils.esc(c)}</th>`);
                html += '</tr></thead><tbody>';
                (result.rows||[]).forEach(row => {
                    html += '<tr>';
                    row.forEach(val => html += `<td style="padding:6px 12px;border-bottom:1px solid var(--border);white-space:nowrap;max-width:300px;overflow:hidden;text-overflow:ellipsis;">${Utils.esc(String(val ?? ''))}</td>`);
                    html += '</tr>';
                });
                html += '</tbody></table></div>';
            } else {
                html += '<div style="text-align:center;padding:40px;color:var(--text-muted);">无数据返回</div>';
            }
            this._showResultModal('SQL测试结果', html);
        } catch(e) {
            const errMsg = e.message || String(e);
            this._showResultModal('SQL错误', `<pre style="background:var(--accent-red-soft);color:var(--accent-red);padding:16px;border-radius:6px;overflow:auto;font-size:13px;white-space:pre-wrap;word-break:break-all;margin:0;">${Utils.esc(errMsg)}</pre>`);
        }
    },

    // ---- 字段映射 ----

    async _onBothTablesSelected() {
        const srcDbId = document.getElementById('sourceDbSelect')?.value;
        const tgtDbId = document.getElementById('targetDbSelect')?.value;
        const srcTable = this._getComboboxValue('srcTable');
        const tgtTable = this._getComboboxValue('tgtTable');
        console.log('[DEBUG] _onBothTablesSelected: params', {srcDbId, tgtDbId, srcTable, tgtTable, _sourceMode: this._sourceMode, _editingTaskId: this._editingTaskId});
        if (!srcDbId || !tgtDbId || !srcTable || !tgtTable) {
            console.warn('[DEBUG] _onBothTablesSelected: EARLY RETURN - missing values', {srcDbId: !srcDbId, tgtDbId: !tgtDbId, srcTable: !srcTable, tgtTable: !tgtTable});
            return;
        }

        let srcFailed = false, tgtFailed = false;
        if (this._sourceMode !== 'sql' || !this._sourceFields.length) {
            try {
                const srcRes = await Api.getTableColumns(srcDbId, srcTable);
                this._sourceFields = this._normalizeFields(srcRes);
                if (!this._sourceFields.length) srcFailed = true;
            } catch (e) { srcFailed = true; console.warn('加载源表字段失败:', e); }
        }
        try {
            const tgtRes = await Api.getTableColumns(tgtDbId, tgtTable);
            this._targetFields = this._normalizeFields(tgtRes);
            if (!this._targetFields.length) tgtFailed = true;
        } catch (e) { tgtFailed = true; console.warn('加载目标表字段失败:', e); }

        if (this._editingTaskId && (srcFailed || tgtFailed)) {
            const mappings = this._fieldMappings;
            if (srcFailed && mappings.length > 0) {
                this._sourceFields = mappings.map(m => ({ name: m.srcField, type: '' })).filter(m => m.name);
            }
            if (tgtFailed && mappings.length > 0) {
                this._targetFields = mappings.map(m => ({ name: m.tgtField, type: '' })).filter(m => m.name);
            }
            App.showToast('部分字段信息未能从数据库加载，已使用缓存映射', 'error');
        }

        const incrSel = document.getElementById('incrFieldSelect');
        if (incrSel) this._populateIncrFieldSelect(incrSel, this._sourceFields);

        if (this._currentIncrField) {
            const incrSel2 = document.getElementById('incrFieldSelect');
            if (incrSel2) incrSel2.value = this._currentIncrField;
        }

        this._restoreFieldMappings(srcTable);
        this._renderFieldMappingArea();
    },

    _restoreFieldMappings(srcTable) {
        if (this._editingTaskId) return;
        this._fieldMappings = [];
        const rule = Store.mappingRules.find(m => m.srcTable === srcTable);
        if (rule && rule.fieldMappings) this._fieldMappings = [...rule.fieldMappings];
    },

    _populateIncrFieldSelect(sel, fields) {
        sel.innerHTML = '';
        const opt = document.createElement('option');
        opt.value = ''; opt.textContent = '-- 选择增量字段 --';
        sel.appendChild(opt);
        const incrCandidates = fields.filter(f => {
            const t = (f.type || '').toUpperCase();
            return /DATE|TIME|TIMESTAMP/.test(t) || /ID|TIME|CREATE|UPDATE|MODIFY/i.test(f.name);
        });
        const candidates = incrCandidates.length ? incrCandidates : fields;
        candidates.forEach(f => {
            const o = document.createElement('option');
            o.value = f.name; o.textContent = f.name + ' (' + (f.type || '未知') + ')';
            sel.appendChild(o);
        });
    },

    _renderFieldMappingArea() {
        const area = document.getElementById('targetDbFormArea');
        console.log('[DEBUG] _renderFieldMappingArea: called', {areaExists: !!area, sourceFieldsLen: this._sourceFields.length, targetFieldsLen: this._targetFields.length, fieldMappingsLen: this._fieldMappings.length});
        if (!area) return;
        const srcFields = this._sourceFields;
        const tgtFields = this._targetFields;
        const isSql = this._sourceMode === 'sql';

        if (!srcFields.length || !tgtFields.length) {
            console.warn('[DEBUG] _renderFieldMappingArea: empty fields, showing message');
            area.innerHTML = `<div style="padding:16px;">
                <div class="mapping-toolbar">
                    <span class="mapping-title-sm">${isSql ? 'SQL字段映射' : '表字段映射'}</span>
                    <button class="btn btn-sm btn-primary" onclick="Config.autoMapFields()" title="根据字段名自动匹配相同名称的字段">${Icons.zap()} 一键映射</button>
                </div>
                <div style="text-align:center;padding:20px;color:var(--text-muted);"><p>${isSql ? '请先点击「解析字段」按钮解析SQL列' : '暂无可用的字段信息'}</p></div>
            </div>`;
            return;
        }

        const mappings = this._fieldMappings.length > 0 ? this._fieldMappings : [{ srcField: '', tgtField: '', isPk: false }];
        console.log('[DEBUG] _renderFieldMappingArea: mappings', mappings.length, 'srcFields', srcFields.length, 'tgtFields', tgtFields.length);

        let rowsHtml = '';
        mappings.forEach((m, i) => {
            const srcVal = Utils.esc(m.srcField || '');
            const tgtVal = Utils.esc(m.tgtField || '');
            const srcType = Utils.esc(srcFields.find(f => f.name === (m.srcField || ''))?.type || '');
            const tgtType = Utils.esc(tgtFields.find(f => f.name === (m.tgtField || ''))?.type || '');
            const pkChecked = m.isPk ? ' checked' : '';

            rowsHtml += `<tr class="field-map-row">
                <td style="padding:6px 10px !important;vertical-align:middle">
                    <div style="display:flex;align-items:center;gap:8px;">
                        <select class="form-select field-src-select" onchange="Config._onSrcFieldChange(this)" data-index="${i}">
                            <option value="">-- 选择源字段 --</option>
                            ` + srcFields.map(sf => {
                                const sel = sf.name === (m.srcField || '') ? ' selected' : '';
                                return `<option value="${Utils.esc(sf.name)}"${sel}>${Utils.esc(sf.name)}</option>`;
                            }).join('') + `
                        </select>
                        <span class="field-type-tag field-src-type">${srcType}</span>
                    </div>
                </td>
                <td class="field-arrow-cell">→</td>
                <td style="padding:6px 10px !important;vertical-align:middle">
                    <div style="display:flex;align-items:center;gap:8px;">
                        <select class="form-select field-tgt-select" onchange="Config._onTgtFieldChange(this)" data-index="${i}">
                            <option value="">-- 不映射 --</option>
                            ` + tgtFields.map(tf => {
                                const sel = tf.name === (m.tgtField || '') ? ' selected' : '';
                                return `<option value="${Utils.esc(tf.name)}"${sel}>${Utils.esc(tf.name)}</option>`;
                            }).join('') + `
                        </select>
                        <span class="field-type-tag tgt field-tgt-type">${tgtType}</span>
                    </div>
                </td>
                <td class="field-pk-cell">
                    <label class="pk-checkbox-wrap" title="勾选作为主键，相同主键执行UPDATE">
                        <input type="checkbox" class="pk-checkbox" data-index="${i}" onchange="Config._onPkChange(this)"${pkChecked}>
                        <span class="pk-indicator">${Icons.key()}</span>
                    </label>
                </td>
                <td class="field-action-cell">
                    <button class="field-del-btn" onclick="Config._removeMappingRow(this)" title="删除此行">${Icons.x()}</button>
                </td>
            </tr>`;
        });

        const mappedCount = mappings.filter(m => m.srcField && m.tgtField).length;
        console.log('[DEBUG] _renderFieldMappingArea: rowsHtml length', rowsHtml.length, 'first 300 chars:', rowsHtml.substring(0, 300));

        area.innerHTML = `<div style="padding:16px;">
            <div class="mapping-toolbar">
                <span class="mapping-title-sm">${isSql ? 'SQL字段映射' : '表字段映射'}
                    <span class="mapped-count">${mappedCount}/${mappings.length}</span>
                </span>
                <button class="btn btn-sm btn-primary" onclick="Config.autoMapFields()" title="根据字段名自动匹配相同名称的字段">${Icons.zap()} 一键映射</button>
                <button class="btn btn-sm" onclick="Config.clearFieldMappings()">清空映射</button>
            </div>
            <div class="table-container field-map-table">
                <table>
                    <thead><tr><th width="32%">${isSql ? 'SQL列' : '源字段'}</th><th width="5%"></th><th width="38%">目标字段</th><th width="8%">主键</th><th width="17%"></th></tr></thead>
                    <tbody id="fieldMappingBody">${rowsHtml}</tbody>
                </table>
            </div>
            <button class="add-mapping-row-btn" onclick="Config._addMappingRow()">+ 添加一行映射</button>
            <div style="margin-top:16px;text-align:right;">
                ${this._editingTaskId ? '<button class="btn" onclick="Config._cancelEdit()" style="margin-right:8px;">取消编辑</button>' : ''}
                <button class="btn btn-primary" onclick="Config.save()">${Icons.save()} ${this._editingTaskId ? '更新配置' : '保存并部署'}</button>
            </div>
        </div>`;
    },

    _normalizeFields(raw) {
        if (!raw) return [];
        if (Array.isArray(raw)) {
            return raw.map((f, i) => {
                if (typeof f === 'string') return { name: f, type: '' };
                if (typeof f === 'object' && f !== null) {
                    return { name: f.name || f.column_name || f.columnName || f.column || f.field || String(f), type: f.type || f.data_type || f.dataType || f.column_type || '' };
                }
                return { name: `field_${i}`, type: '' };
            });
        }
        if (typeof raw === 'object') {
            const arr = raw.list || raw.items || raw.columns || raw.data || raw.rows;
            if (Array.isArray(arr)) return this._normalizeFields(arr);
        }
        return [];
    },

    _onStrategyChange() {
        const v = document.getElementById('syncStrategySelect')?.value;
        const g = document.getElementById('incrementFieldGroup');
        if (g) g.style.display = v === 'incremental' ? '' : 'none';
    },

    _onNoUpdateChange() {},

    _onTaskGroupSelectChange() {
        const selVal = document.getElementById('taskGroupSelect')?.value;
        const input = document.getElementById('taskGroupInput');
        if (input) input.style.display = selVal === '__new__' ? '' : 'none';
    },

    _goToSchedulePage() {
        const Schedules = window.Schedules;
        if (typeof Schedules !== 'undefined') {
            Schedules._onScheduleCreated = (newId) => {
                this._refreshScheduleSelect(newId);
            };
        }
        App.switchPage('schedules');
    },

    // ---- 一键映射/清空/添加行 ----

    _addMappingRow() {
        this._fieldMappings.push({ srcField: '', tgtField: '', isPk: false });
        this._renderFieldMappingAreaScroll();
    },

    _removeMappingRow(el) {
        const row = el.closest('tr');
        if (!row) return;
        const idx = Array.from(row.parentNode.children).indexOf(row);
        if (idx >= 0 && idx < this._fieldMappings.length) {
            this._fieldMappings.splice(idx, 1);
            if (this._fieldMappings.length === 0) this._fieldMappings.push({ srcField: '', tgtField: '', isPk: false });
            this._renderFieldMappingAreaScroll();
        }
    },

    autoMapFields() {
        const srcFields = this._sourceFields || [];
        const tgtFields = this._targetFields || [];
        if (!srcFields.length || !tgtFields.length) { App.showToast('源表或目标表字段为空，无法自动映射', 'error'); return; }

        const getName = (f) => f.name || f.column || f.columnName || String(f);
        this._fieldMappings = [];
        const tgtNamesLower = new Map();
        tgtFields.forEach(tf => { tgtNamesLower.set(getName(tf).toLowerCase(), getName(tf)); });
        const matchedTgtNames = new Set();

        let matched = 0;
        srcFields.forEach(sf => {
            const srcName = getName(sf);
            const matchedTgtName = tgtNamesLower.get(srcName.toLowerCase());
            if (matchedTgtName) {
                const isPk = /\bid$/i.test(srcName) && !/^\w*_?(fk|ref|parent|up)\w*$/i.test(srcName);
                this._fieldMappings.push({ srcField: srcName, tgtField: matchedTgtName, isPk });
                matched++;
                matchedTgtNames.add(srcName.toLowerCase());
            } else {
                const isPk = /\bid$/i.test(srcName) && !/^\w*_?(fk|ref|parent|up)\w*$/i.test(srcName);
                this._fieldMappings.push({ srcField: srcName, tgtField: '', isPk });
            }
        });
        tgtFields.forEach(tf => {
            const tgtName = getName(tf);
            if (!matchedTgtNames.has(tgtName.toLowerCase())) {
                this._fieldMappings.push({ srcField: '', tgtField: tgtName, isPk: false });
            }
        });

        this._renderFieldMappingArea();
        const pkCount = this._fieldMappings.filter(m => m.isPk).length;
        App.showToast(`一键映射完成，自动匹配 ${matched} 个，未匹配 ${this._fieldMappings.length - matched} 个，识别 ${pkCount} 个主键`);
    },

    clearFieldMappings() {
        this._fieldMappings = [{ srcField: '', tgtField: '', isPk: false }];
        this._renderFieldMappingArea();
        App.showToast('已清空所有映射');
    },

    _renderFieldMappingAreaScroll() {
        const container = document.querySelector('.field-map-table');
        const scrollTop = container ? container.scrollTop : 0;
        this._renderFieldMappingArea();
        const newContainer = document.querySelector('.field-map-table');
        if (newContainer) newContainer.scrollTop = scrollTop;
    },

    // ---- 映射行事件 ----

    _onSrcFieldChange(selectEl) {
        const idx = parseInt(selectEl.dataset.index);
        if (idx >= 0 && idx < this._fieldMappings.length) {
            this._fieldMappings[idx].srcField = selectEl.value || '';
        }
        const srcType = this._sourceFields.find(f => f.name === selectEl.value)?.type || '';
        const typeEl = selectEl.closest('td').querySelector('.field-src-type');
        if (typeEl) typeEl.textContent = srcType;
        this._updateMappedCount();
    },

    _onTgtFieldChange(selectEl) {
        const idx = parseInt(selectEl.dataset.index);
        if (idx >= 0 && idx < this._fieldMappings.length) {
            this._fieldMappings[idx].tgtField = selectEl.value || '';
        }
        const tgtType = this._targetFields.find(f => f.name === selectEl.value)?.type || '';
        const typeEl = selectEl.closest('td').querySelector('.field-tgt-type');
        if (typeEl) typeEl.textContent = tgtType;
        this._updateMappedCount();
    },

    _onPkChange(checkboxEl) {
        const idx = parseInt(checkboxEl.dataset.index);
        if (idx >= 0 && idx < this._fieldMappings.length) {
            this._fieldMappings[idx].isPk = checkboxEl.checked;
        }
    },

    _updateMappedCount() {
        const countEl = document.querySelector('.mapped-count');
        if (countEl) {
            const mapped = this._fieldMappings.filter(m => m.srcField && m.tgtField).length;
            countEl.textContent = `${mapped}/${this._fieldMappings.length}`;
        }
    },

    // ---- Select/Group 刷新 ----

    _refreshGroupSelect(currentVal, forModal) {
        const sel = document.getElementById(forModal ? 'groupModalSelect' : 'taskGroupSelect');
        if (!sel) return;
        const groups = new Set();
        (this._taskList || []).forEach(t => { const g = this._getTaskGroup(t); if (g) groups.add(g); });
        sel.innerHTML = '<option value="">-- 不分组 --</option>' +
            Array.from(groups).map(g => `<option value="${Utils.esc(g)}">${Utils.esc(g)}</option>`).join('') +
            '<option value="__new__">+ 新建分组...</option>';
        if (currentVal) sel.value = currentVal;
    },

    async _refreshScheduleSelect(currentId) {
        const sel = document.getElementById('syncScheduleSelect');
        if (!sel) return;
        try {
            const schedules = await Api.getSchedules();
            sel.innerHTML = '<option value="">-- 选择或创建调度 --</option>' +
                (Array.isArray(schedules) ? schedules : []).map(s =>
                    `<option value="${Utils.esc(s.id)}">${Utils.esc(s.name)} ${s.cron ? '(' + Utils.esc(s.cron) + ')' : ''}</option>`
                ).join('');
            if (currentId) sel.value = currentId;
        } catch(e) {}
    },

    // ---- 保存 ----

    async save() {
        const srcSel = document.getElementById('sourceDbSelect');
        const tgtSel = document.getElementById('targetDbSelect');
        const strategySel = document.getElementById('syncStrategySelect');
        const schedSel = document.getElementById('syncScheduleSelect');
        const incrSel = document.getElementById('incrFieldSelect');

        if (!srcSel?.value || !tgtSel?.value) { App.showToast('请选择源库和目标库', 'error'); return; }

        let srcTable, srcSql;
        if (this._sourceMode === 'sql') {
            const sqlName = document.getElementById('srcSqlName')?.value?.trim();
            if (!sqlName) { App.showToast('自定义SQL模式下请填写源表名称', 'error'); return; }
            srcSql = this._sqlEditor?.getValue?.()?.trim() || '';
            if (!srcSql) { App.showToast('请输入SQL语句', 'error'); return; }
            srcTable = sqlName;
        } else {
            srcTable = this._getComboboxValue('srcTable');
            srcSql = null;
        }

        const tgtTable = this._getComboboxValue('tgtTable');
        if (!srcTable || !tgtTable) { App.showToast('请选择源表和目标表', 'error'); return; }

        const strategy = strategySel?.value || 'incremental';
        if (strategy === 'incremental' && !incrSel?.value) {
            App.showToast('增量同步必须选择一个基准字段', 'error'); return;
        }
        if (!schedSel?.value) { App.showToast('请选择执行调度', 'error'); return; }

        const validMappings = this._fieldMappings.filter(m => m.srcField && m.tgtField);
        if (!validMappings.length) { App.showToast('请至少配置一个有效的字段映射', 'error'); return; }

        const pkFields = validMappings.filter(m => m.isPk);
        if (!pkFields.length) { App.showToast('请至少勾选一个字段作为主键', 'error'); return; }

        this._currentIncrField = incrSel?.value || '';

        const isEdit = !!this._editingTaskId;
        const groupSel = document.getElementById('taskGroupSelect');
        const groupInput = document.getElementById('taskGroupInput');
        const groupName = groupInput?.value?.trim() || groupSel?.value || '';

        const data = {
            source_db_id: srcSel.value,
            target_db_id: tgtSel.value,
            src_table: srcTable,
            src_sql: srcSql || null,
            tgt_table: tgtTable,
            strategy: strategy,
            schedule_id: schedSel.value,
            increment_field: incrSel?.value || null,
            field_mappings: validMappings,
            task_group: groupName || null,
            no_update: !!document.getElementById('noUpdateCheck')?.checked,
            fetch_mode: document.getElementById('fetchModeSelect')?.value || 'parallel'
        };

        if (isEdit) {
            data.id = this._editingTaskId;
        }

        try {
            await Api.saveSyncTask(data);
            App.showSaveIndicator();
            App.showToast(isEdit ? '配置已更新并部署' : '配置已保存并部署');
            this._editingTaskId = null;
            this._loadSyncTasks();
        } catch (error) {}
    }
};

// ---- Combobox 工具函数 ----

Config._bindCombobox = function(id, placeholder) {
    const wrap = document.getElementById(id + 'ComboboxWrap');
    if (!wrap) return;
    wrap.innerHTML = `<div class="combobox-wrap" id="combobox-${id}">
        <input class="form-input combobox-input" id="${id}Input" placeholder="${placeholder}" autocomplete="off" onfocus="Config._onComboboxFocus('${id}')" oninput="Config._onComboboxInput('${id}')" onblur="Config._onComboboxBlur('${id}')">
        <input type="hidden" id="${id}Value">
        <div class="combobox-dropdown" id="${id}Dropdown"></div>
    </div>`;
};

Config._setComboboxValue = function(id, val) {
    const input = document.getElementById(id + 'Input');
    if (input) { input.value = val; input.dataset.selected = val || ''; }
};

Config._getComboboxValue = function(id) {
    const input = document.getElementById(id + 'Input');
    return input ? (input.dataset.selected || '') : '';
};

Config._onComboboxInput = function(id) { Config._refreshDropdown(id, true); };
Config._onComboboxFocus = function(id) { Config._refreshDropdown(id, true); };
Config._onComboboxBlur = function(id) { setTimeout(() => Config._hideDropdown(id), 200); };

Config._hideDropdown = function(id) {
    const dd = document.getElementById(id + 'Dropdown');
    if (dd) dd.classList.remove('show');
};

Config._refreshDropdown = function(id, show) {
    const dd = document.getElementById(id + 'Dropdown');
    if (!dd) return;
    const input = document.getElementById(id + 'Input');
    const kw = (input?.value || '').toLowerCase();
    const items = Config['_' + id + 'Items'] || [];
    const filtered = kw ? items.filter(item => item.toLowerCase().includes(kw)) : items;
    if (!filtered.length) {
        dd.innerHTML = '<div class="combobox-item" style="color:var(--text-muted);">无匹配结果</div>';
    } else {
        dd.innerHTML = filtered.map(item => {
            const esc = Utils.esc(item);
            if (!kw) return `<div class="combobox-item" onmousedown="Config._selectComboboxItem('${id}','${esc}')">${esc}</div>`;
            const re = new RegExp('(' + Utils.regEscape(kw) + ')', 'gi');
            return `<div class="combobox-item" onmousedown="Config._selectComboboxItem('${id}','${esc}')">${esc.replace(re, '<b>$1</b>')}</div>`;
        }).join('');
    }
    if (show) dd.classList.add('show');
};

Config._selectComboboxItem = function(id, val) {
    const input = document.getElementById(id + 'Input');
    if (input) { input.value = val; input.dataset.selected = val; }
    this._hideDropdown(id);
    if (id === 'srcTable') {
        Config._onSrcTableSelected(val);
    } else if (id === 'tgtTable') {
        Config._onTgtTableSelected(val);
    }
};

Config._onSrcTableSelected = function(val) {
    const tgtInput = document.getElementById('tgtTableInput');
    if (tgtInput && document.getElementById('targetDbSelect')?.value) {
        tgtInput.disabled = false;
        tgtInput.placeholder = '输入或选择目标表...';
        Config._loadTablesToCombobox(document.getElementById('targetDbSelect').value, 'tgtTable', false);
    }
    const srcTable = Config._getComboboxValue('srcTable');
    const tgtTable = Config._getComboboxValue('tgtTable');
    if (srcTable && tgtTable) Config._onBothTablesSelected();
};

Config._onTgtTableSelected = function(val) {
    const srcTable = Config._getComboboxValue('srcTable');
    if (srcTable && val) Config._onBothTablesSelected();
};

Config._loadTablesToCombobox = async function(dbId, comboboxId, isSource) {
    try {
        const tables = await Api.getTables(dbId, isSource ? 'source' : 'target');
        const tableNames = Array.isArray(tables) ? (tables.length > 0 && typeof tables[0] === 'string' ? tables : tables.map(t => t.name || t.tableName || t.table_name || String(t))) : [];
        Config['_' + comboboxId + 'Items'] = tableNames;
        Config._refreshDropdown(comboboxId, false);
    } catch (error) { console.error('加载表列表失败:', error); }
};
