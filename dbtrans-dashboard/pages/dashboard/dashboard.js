var Dashboard = {
    perfChart: null,
    pieChart:  null,
    refreshTimer: null,

    async initCharts() {
        const perfCtx = document.getElementById('perfChart');
        if (!perfCtx) return;

        if (this.perfChart) this.perfChart.destroy();
        if (this.pieChart) this.pieChart.destroy();

        try {
            const [perfData, distData, overview, runtime] = await Promise.all([
                Api.getDashboardMetrics(30, 300),
                Api.getSyncDistribution(),
                Api.getDashboardOverview(),
                Api.getRuntimeMetrics()
            ]);

            this._updateOverview(overview);
            this._updateRuntime(runtime);

            this.perfChart = new Chart(perfCtx.getContext('2d'), {
                type: 'line',
                data: {
                    labels: perfData?.labels || ['09:05','09:10','09:15','09:20','09:25','09:30'],
                    datasets: [
                        {
                            label: 'RPS',
                            data: perfData?.rps || [4200, 4500, 4700, 4600, 4900, 4821],
                            borderColor: '#1e5fbb',
                            backgroundColor: 'rgba(30,95,187,.08)',
                            fill: true,
                            tension: .35,
                            pointRadius: 3,
                            pointHoverRadius: 5,
                            borderWidth: 2,
                            yAxisID: 'y'
                        },
                        {
                            label: 'Latency (ms)',
                            data: perfData?.latency_ms || [1.8, 1.55, 1.45, 1.52, 1.44, 1.42],
                            borderColor: '#F0B429',
                            backgroundColor: 'transparent',
                            fill: false,
                            tension: .35,
                            pointRadius: 3,
                            pointHoverRadius: 5,
                            borderWidth: 2,
                            yAxisID: 'y1'
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    animation: { duration: 800 },
                    interaction: { mode: 'index', intersect: false },
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            backgroundColor: '#ffffff',
                            titleColor: '#0d253d',
                            bodyColor: '#64748d',
                            borderColor: '#e3e8ee',
                            borderWidth: 1,
                            cornerRadius: 8,
                            padding: 10
                        }
                    },
                    scales: {
                        x: {
                            grid: { color: 'rgba(0,0,0,0.06)', drawBorder: false },
                            ticks: { color: '#64748d', font: { size: 11 } }
                        },
                        y: {
                            position: 'left',
                            grid: { color: 'rgba(0,0,0,0.06)', drawBorder: false },
                            ticks: { color: '#64748d', font: { size: 11 } },
                            min: 0
                        },
                        y1: {
                            position: 'right',
                            grid: { draw: false },
                            ticks: { color: '#64748d', font: { size: 11 } },
                            min: 0
                        }
                    }
                }
            });

            const pieCtx = document.getElementById('pieChart');
            this.pieChart = new Chart(pieCtx.getContext('2d'), {
                type: 'doughnut',
                data: {
                    labels: distData?.labels || ['users', 'user_auth', 'login_logs', 'orders', 'sessions'],
                    datasets: [{
                        data: distData?.data || [35, 22, 18, 15, 10],
                        backgroundColor: ['#1e5fbb', '#f59e0b', '#16a34a', '#dc2626', '#64748b'],
                        borderWidth: 0,
                        hoverOffset: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    cutout: '65%',
                    animation: { duration: 800 },
                    plugins: {
                        legend: { display: false },
                        tooltip: {
                            backgroundColor: '#ffffff',
                            titleColor: '#0d253d',
                            bodyColor: '#64748d',
                            borderColor: '#e3e8ee',
                            borderWidth: 1,
                            cornerRadius: 8,
                            callbacks: { label: ctx => ` ${ctx.label}: ${ctx.raw}%` }
                        }
                    }
                }
            });
        } catch (error) {
            console.error('加载看板数据失败:', error);
        }
    },

    _updateOverview(data) {
        if (!data) return;

        const formatNum = (n) => {
            if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
            if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
            return n.toLocaleString();
        };

        // 更新数值span，保留单位span
        const setStatNum = (elId, val) => {
            const numEl = document.querySelector('#' + elId + ' .dash-stat-num');
            if (numEl) numEl.textContent = val;
        };
        setStatNum('dashRps', formatNum(data.current_rps || 0));
        setStatNum('dashLatency', data.latency_ms || 0);
        setStatNum('dashRows', (data.today_rows_synced || 0).toLocaleString());
        setStatNum('dashBw', data.throughput_mbps || 0);

        const fullSyncEl = document.getElementById('dashFullSync');
        if (fullSyncEl) fullSyncEl.textContent = `${data.full_sync_progress || 0}%`;

        const cdcEl = document.getElementById('dashCdcStatus');
        if (cdcEl) {
            cdcEl.textContent = data.cdc_status === 'active' ? '活跃中' : (data.cdc_status === 'idle' ? '空闲' : '异常');
            cdcEl.className = `bottom-value ${data.cdc_status === 'active' ? 'value-green' : (data.cdc_status === 'idle' ? 'value-blue' : 'value-red')}`;
        }

        const cdcInfoEl = document.getElementById('dashCdcInfo');
        if (cdcInfoEl && data.last_cdc_event) {
            cdcInfoEl.textContent = `系统运行正常，最近一次CDC事件: ${data.last_cdc_event.table}表 ${data.last_cdc_event.operation}操作`;
        }
    },

    _updateRuntime(data) {
        if (!data) return;
        const setEl = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };

        setEl('dashSyncThreads', (data.sync_active_threads||0) + '/' + (data.sync_pool_size||0));
        setEl('dashMergeThreads', (data.merge_active_threads||0) + '/' + (data.merge_pool_size||0));
        setEl('dashSyncQueue', data.sync_queue_size != null ? data.sync_queue_size : '-');
        setEl('dashTodayRate', data.today_success_rate || '-');

        // 进度卡 — 检查是否有进展中的任务
        // 通过overview的cdc_status判断, active=有运行中的任务
        if (data.sync_active_threads > 0) {
            document.getElementById('dashProgressCard').style.display = 'block';
        }
    },

    async refresh() {
        await this.initCharts();
    },

    changeRefreshRate(rate) {
        if (this.refreshTimer) clearInterval(this.refreshTimer);
        const seconds = parseInt(rate);
        this.refreshTimer = setInterval(() => this.refresh(), seconds * 1000);
        App.showToast(`刷新频率已调整为 ${rate}秒`, 'success');
    },

    render() {
        this.initCharts();
        if (this.refreshTimer) clearInterval(this.refreshTimer);
        this.refreshTimer = setInterval(() => this.refresh(), 10000);
    },

    destroy() {
        if (this.refreshTimer) {
            clearInterval(this.refreshTimer);
            this.refreshTimer = null;
        }
        if (this.perfChart) { this.perfChart.destroy(); this.perfChart = null; }
        if (this.pieChart) { this.pieChart.destroy(); this.pieChart = null; }
    }
};
