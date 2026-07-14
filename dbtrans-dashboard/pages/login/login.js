var Login = {
    async doLogin() {
        const username = document.getElementById('loginUsername').value.trim();
        const password = document.getElementById('loginPassword').value;
        const errEl = document.getElementById('loginError');
        if (!username || !password) {
            errEl.style.display = ''; errEl.textContent = '请输入用户名和密码'; return;
        }
        errEl.style.display = 'none';
        try {
            const res = await fetch(API_BASE_URL + '/api/v1/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const data = await res.json();
            if (data.code !== 0) {
                errEl.style.display = ''; errEl.textContent = data.message || '登录失败'; return;
            }
            localStorage.setItem('token', data.data.token);
            localStorage.setItem('user', JSON.stringify({ username: data.data.username, realName: data.data.realName }));
            App.switchPage('dashboard');
        } catch(e) {
            errEl.style.display = ''; errEl.textContent = '网络错误，请重试';
        }
    }
};

// 回车登录
document.getElementById('loginPassword').addEventListener('keydown', function(e) {
    if (e.key === 'Enter') Login.doLogin();
});
