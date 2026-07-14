/**
 * DBTrans - Bootstrap / Entry Point
 */

// 1) Initialize data store
Store.init();

// 2) Check auth and load initial page
async function initApp() {
    var token = localStorage.getItem('token');
    if (token) {
        // Restore user display in nav
        var userStr = localStorage.getItem('user');
        if (userStr) {
            try {
                var user = JSON.parse(userStr);
                var el = document.getElementById('navUserName');
                if (el) el.textContent = user.realName || user.username;
                document.getElementById('navUserArea').style.display = '';
            } catch(e) {}
        }
        await App.switchPage('dashboard');
    } else {
        await App.switchPage('login');
    }
}

initApp().catch(function(err) {
    console.error('应用初始化失败:', err);
});

// 3) Start time updater
App.updateTime();
setInterval(App.updateTime, 30000);
