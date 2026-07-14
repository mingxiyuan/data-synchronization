var LogDetail = {

    render(detail) {
        const basic = detail.basic || {};
        const stats = detail.stats || {};
        const timeline = detail.timeline || [];
        const errors = basic.errors || detail.errors;

        // 基本信息
        document.getElementById('detailJobId').textContent = basic.job_id || '-';
        document.getElementById('detailTaskId').textContent = basic.job_id || '-';
        document.getElementById('detailExecTime').textContent = Utils.formatTime(basic.exec_time);

        // 状态 — 用 createElement
        const statusMap = { success:{cls:'success',txt:'SUCCESS'}, fail:{cls:'fail',txt:'FAILED'}, warning:{cls:'warning',txt:'WARNING'} };
        const st = statusMap[basic.status] || statusMap.success;
        const statusEl = document.getElementById('detailStatus');
        statusEl.innerHTML = '';
        const statusSpan = document.createElement('span');
        statusSpan.className = 'status-tag ' + st.cls;
        statusSpan.textContent = st.txt;
        statusEl.appendChild(statusSpan);

        document.getElementById('detailDuration').textContent = basic.duration || '-';
        document.getElementById('detailRowsTotal').textContent = basic.rows_total || '-';
        document.getElementById('detailSourceDb').textContent = basic.source_database || '-';
        document.getElementById('detailTargetDb').textContent = basic.target_database || '-';

        // 错误信息
        const errorBlockEl = document.getElementById('detailErrorBlock');
        if (errorBlockEl) {
            if (errors) {
                const errorDiv = document.createElement('div');
                errorDiv.className = 'detail-error-block';
                errorDiv.innerHTML = `<div class="detail-error-title">${Icons.alert()} 失败原因</div>
                    <div class="detail-error-content">${Array.isArray(errors) ? errors.map(e => Utils.esc(e)).join('<br>') : Utils.esc(errors)}</div>`;
                errorBlockEl.innerHTML = '';
                errorBlockEl.appendChild(errorDiv);
            } else {
                errorBlockEl.innerHTML = '';
            }
        }

        // 同步统计
        document.getElementById('detailInsertCount').textContent = (stats.insert_count || 0).toLocaleString();
        document.getElementById('detailUpdateCount').textContent = (stats.update_count || 0).toLocaleString();
        document.getElementById('detailErrorCount').textContent = (stats.error_count || 0).toLocaleString();

        // 执行日志
        const timelineEl = document.getElementById('detailTimeline');
        timelineEl.innerHTML = '';
        const tpl = document.getElementById('tpl-timeline-item');
        timeline.forEach(t => {
            const item = tpl.content.firstElementChild.cloneNode(true);
            item.querySelector('.log-timeline-time').textContent = t.time;
            item.querySelector('.log-timeline-msg').textContent = t.event;
            timelineEl.appendChild(item);
        });
    }
};
