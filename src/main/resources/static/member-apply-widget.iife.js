/**
 * Member Apply Widget - 成员申请弹窗组件
 * 使用方式: MemberApplyWidget.open()
 *
 * 依赖插件: PluginMembers
 * API 端点: /apis/api.member.plugin.halo.run/v1alpha1
 */

(function(window) {
  'use strict';

  const API_BASE = '/apis/api.member.plugin.halo.run/v1alpha1';
  const widgetScriptUrl = document.currentScript && document.currentScript.src;
  const WIDGET_ASSET_BASE = widgetScriptUrl
    ? new URL('.', widgetScriptUrl).href
    : '/plugins/PluginMembers/assets/static/';

  function parseJsonResponse(response) {
    return response.text().then(function(text) {
      var contentType = response.headers.get('content-type') || '';
      var redirectedToLogin = response.redirected
        && /\/login(?:[?#]|$)/.test(response.url || '');
      var data = null;
      try {
        data = text ? JSON.parse(text) : null;
      } catch (_) {
        if (response.status === 302 || redirectedToLogin) {
          throw new Error('成员公开接口未授权，请管理员在 Halo 控制台重新安装或升级成员插件');
        }
        throw new Error(!contentType.toLowerCase().includes('json')
          ? '成员接口返回格式错误'
          : '成员接口响应解析失败');
      }
      if (!response.ok) {
        var failure = new Error(data && (data.message || data.detail) || ('HTTP ' + response.status));
        failure.status = response.status;
        failure.problemType = (data && data.type) || '';
        throw failure;
      }
      return data;
    });
  }

  let modal = null;
  let form = null;
  let tip = null;
  let submitBtn = null;
  let groupSelect = null;
  let groupsLoaded = false;
  let captchaRow = null;
  let captchaImage = null;
  let captchaInput = null;
  let captchaHint = null;
  let captchaChallengeId = '';
  let captchaRequired = false;
  let captchaLoading = false;

  function createModal() {
    const html = `
      <div id="memberApplyModal" class="member-apply-widget-modal" onclick="if(event.target===this)MemberApplyWidget.close()">
        <div class="member-apply-widget-modal-box" role="dialog" aria-modal="true" aria-labelledby="memberApplyTitle">
          <div class="member-apply-widget-modal-header">
            <h3 id="memberApplyTitle" class="member-apply-widget-modal-title">申请加入成员</h3>
            <button type="button" class="member-apply-widget-modal-close" onclick="MemberApplyWidget.close()" aria-label="关闭">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M18 6 6 18M6 6l12 12"></path>
              </svg>
            </button>
          </div>
          <form id="memberApplyForm" class="member-apply-widget-modal-form" onsubmit="return false">
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-qq">QQ 号 <span class="member-apply-widget-form-req">*</span></label>
              <input class="member-apply-widget-form-input" id="ma-qq" name="qq" type="text" required pattern="\\d{5,12}" placeholder="5-12 位数字" />
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-displayName">账号名称 <span class="member-apply-widget-form-req">*</span></label>
              <input class="member-apply-widget-form-input" id="ma-displayName" name="displayName" type="text" required maxlength="50" placeholder="2-50 字符" />
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-school">学校 <span class="member-apply-widget-form-req">*</span></label>
              <input class="member-apply-widget-form-input" id="ma-school" name="school" type="text" required placeholder="所在学校全称" />
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-email">邮箱 <span class="member-apply-widget-form-req">*</span></label>
              <input class="member-apply-widget-form-input" id="ma-email" name="email" type="email" required maxlength="254" autocomplete="email" placeholder="用于接收审核结果通知" />
              <div class="member-apply-widget-form-hint">请填写常用邮箱：申请受理、审核通过／未通过和 QQ 链接补全通知都会发到这个地址，请勿使用临时邮箱。邮箱仅管理员可见。</div>
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label">QQ 好友链接（上传二维码自动解析）</label>
              <div class="member-apply-widget-qq-row">
                <input class="member-apply-widget-form-input" id="ma-qqFriendLink" name="qqFriendLink" type="url" placeholder="https://qm.qq.com/...（可选）" />
                <button type="button" class="member-apply-widget-btn-mini" onclick="document.getElementById('ma-qrFile').click()">上传二维码</button>
              </div>
              <input type="file" id="ma-qrFile" accept="image/*" style="display:none" onchange="MemberApplyWidget.parseQrCode(this)" />
              <div class="member-apply-widget-form-hint" id="ma-qrHint">支持上传 QQ 二维码图片，自动解析好友链接并填入。</div>
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-groupName">申请分组 <span class="member-apply-widget-form-req">*</span></label>
              <select class="member-apply-widget-form-input" id="ma-groupName" name="groupName" required>
                <option value="">加载中...</option>
              </select>
              <div class="member-apply-widget-form-hint" id="ma-groupHint"></div>
            </div>
            <div class="member-apply-widget-form-row" id="ma-captchaRow" hidden>
              <label class="member-apply-widget-form-label" for="ma-captchaCode">图形验证码 <span class="member-apply-widget-form-req">*</span></label>
              <div class="member-apply-widget-captcha-row">
                <input class="member-apply-widget-form-input" id="ma-captchaCode" name="captchaCode" type="text" maxlength="5" autocomplete="off" spellcheck="false" placeholder="5 位字符" />
                <button type="button" class="member-apply-widget-captcha-btn" id="ma-captchaRefresh" title="换一张验证码" aria-label="换一张验证码">
                  <img id="ma-captchaImage" alt="图形验证码" width="160" height="48" />
                </button>
              </div>
              <div class="member-apply-widget-form-hint" id="ma-captchaHint">点击图片可以换一张验证码。</div>
            </div>
            <div class="member-apply-widget-form-tip" id="ma-tip"></div>
            <div class="member-apply-widget-modal-footer">
              <button type="button" class="member-apply-widget-btn-secondary" onclick="MemberApplyWidget.close()">取消</button>
              <button type="button" class="member-apply-widget-btn-primary" id="ma-submit" onclick="MemberApplyWidget.submit()">提交申请</button>
            </div>
          </form>
        </div>
      </div>
    `;

    const style = `
      <style>
        :root {
          --member-apply-widget-primary: var(--primary, #D13E43);
          --member-apply-widget-primary-light: var(--primary-light, #E8686D);
          --member-apply-widget-bg-primary: var(--bg-primary, #ffffff);
          --member-apply-widget-bg-secondary: var(--bg-secondary, #f9fafb);
          --member-apply-widget-text-primary: var(--text-primary, #111827);
          --member-apply-widget-text-secondary: var(--text-secondary, #4b5563);
          --member-apply-widget-text-tertiary: var(--text-tertiary, #9ca3af);
          --member-apply-widget-border-color: var(--border-color, #e5e7eb);
          --member-apply-widget-radius-sm: 6px;
          --member-apply-widget-radius-md: 8px;
          --member-apply-widget-radius-lg: 12px;
          --member-apply-widget-transition-fast: 150ms cubic-bezier(0.4, 0, 0.2, 1);
          --member-apply-widget-transition-normal: 300ms cubic-bezier(0.4, 0, 0.2, 1);
        }

        .member-apply-widget-modal {
          position: fixed;
          inset: 0;
          background: rgba(0,0,0,0.5);
          z-index: 1000;
          display: none;
          align-items: center;
          justify-content: center;
          padding: 1rem;
          opacity: 0;
          transition: opacity var(--member-apply-widget-transition-normal);
        }

        .member-apply-widget-modal.is-open {
          display: flex;
          opacity: 1;
        }

        .member-apply-widget-modal-box {
          background: var(--member-apply-widget-bg-primary);
          border-radius: var(--member-apply-widget-radius-lg);
          width: 100%;
          max-width: 480px;
          max-height: 90vh;
          overflow-y: auto;
          box-shadow: 0 20px 60px rgba(0,0,0,0.25);
        }

        .member-apply-widget-modal-header {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 1rem;
          border-bottom: 1px solid var(--member-apply-widget-border-color);
        }

        .member-apply-widget-modal-title {
          font-size: 1.0625rem;
          font-weight: 600;
          color: var(--member-apply-widget-text-primary);
          margin: 0;
        }

        .member-apply-widget-modal-close {
          padding: 4px;
          border: none;
          background: transparent;
          color: var(--member-apply-widget-text-secondary);
          cursor: pointer;
          border-radius: var(--member-apply-widget-radius-sm);
          transition: background var(--member-apply-widget-transition-fast);
        }

        .member-apply-widget-modal-close:hover {
          background: var(--member-apply-widget-bg-secondary);
        }

        .member-apply-widget-modal-form {
          padding: 1rem;
          display: flex;
          flex-direction: column;
          gap: 1rem;
        }

        .member-apply-widget-form-row {
          display: flex;
          flex-direction: column;
          gap: 6px;
        }

        .member-apply-widget-form-row[hidden] {
          display: none;
        }

        .member-apply-widget-captcha-row {
          display: flex;
          align-items: center;
          gap: 8px;
        }

        .member-apply-widget-captcha-btn {
          flex: 0 0 auto;
          padding: 0;
          border: 1px solid var(--member-apply-widget-border-color);
          border-radius: var(--member-apply-widget-radius-sm);
          background: var(--member-apply-widget-bg-primary);
          line-height: 0;
          cursor: pointer;
        }

        .member-apply-widget-captcha-btn:hover { opacity: 0.88; }

        .member-apply-widget-captcha-btn img {
          display: block;
          width: 160px;
          height: 48px;
        }

        .member-apply-widget-form-label {
          font-size: 0.8125rem;
          font-weight: 500;
          color: var(--member-apply-widget-text-primary);
        }

        .member-apply-widget-form-req {
          color: var(--member-apply-widget-primary);
        }

        .member-apply-widget-form-input {
          padding: 8px 12px;
          border: 1px solid var(--member-apply-widget-border-color);
          border-radius: var(--member-apply-widget-radius-sm);
          background: var(--member-apply-widget-bg-primary);
          color: var(--member-apply-widget-text-primary);
          font-size: 0.875rem;
          transition: border-color var(--member-apply-widget-transition-fast);
        }

        .member-apply-widget-form-input:focus {
          outline: none;
          border-color: var(--member-apply-widget-primary);
        }

        .member-apply-widget-form-tip {
          font-size: 0.8125rem;
          min-height: 1.2em;
        }

        .member-apply-widget-form-tip-success {
          color: #16a34a;
        }

        .member-apply-widget-form-tip-error {
          color: var(--member-apply-widget-primary);
        }

        .member-apply-widget-modal-footer {
          display: flex;
          justify-content: flex-end;
          gap: 0.5rem;
          margin-top: 0.5rem;
        }

        .member-apply-widget-btn-primary,
        .member-apply-widget-btn-secondary {
          padding: 8px 16px;
          border-radius: var(--member-apply-widget-radius-sm);
          font-size: 0.875rem;
          font-weight: 500;
          cursor: pointer;
          transition: opacity var(--member-apply-widget-transition-fast);
          border: 1px solid transparent;
        }

        .member-apply-widget-btn-primary {
          background: var(--member-apply-widget-primary);
          color: #fff;
          border-color: var(--member-apply-widget-primary);
        }

        .member-apply-widget-btn-primary:hover {
          opacity: 0.88;
        }

        .member-apply-widget-btn-primary:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }

        .member-apply-widget-btn-secondary {
          background: transparent;
          color: var(--member-apply-widget-text-secondary);
          border-color: var(--member-apply-widget-border-color);
        }

        .member-apply-widget-btn-secondary:hover {
          background: var(--member-apply-widget-bg-secondary);
        }

        .member-apply-widget-qq-row {
          display: flex;
          gap: 8px;
          align-items: stretch;
        }

        .member-apply-widget-qq-row .member-apply-widget-form-input {
          flex: 1 1 auto;
          min-width: 0;
        }

        .member-apply-widget-btn-mini {
          flex: 0 0 auto;
          padding: 8px 14px;
          border-radius: var(--member-apply-widget-radius-sm);
          background: var(--member-apply-widget-primary);
          color: #fff;
          border: 1px solid var(--member-apply-widget-primary);
          font-size: 0.8125rem;
          font-weight: 500;
          cursor: pointer;
          white-space: nowrap;
          transition: opacity var(--member-apply-widget-transition-fast);
        }

        .member-apply-widget-btn-mini:hover { opacity: 0.88; }
        .member-apply-widget-btn-mini:disabled { opacity: 0.5; cursor: not-allowed; }

        .member-apply-widget-form-hint {
          font-size: 0.75rem;
          color: var(--member-apply-widget-text-tertiary);
          line-height: 1.5;
        }

        .member-apply-widget-form-hint.is-success { color: #16a34a; }
        .member-apply-widget-form-hint.is-error { color: var(--member-apply-widget-primary); }

        @media (max-width: 640px) {
          .member-apply-widget-modal-box {
            max-width: 100%;
          }
          .member-apply-widget-qq-row {
            flex-direction: column;
          }
          .member-apply-widget-captcha-row {
            flex-direction: column;
            align-items: stretch;
          }
        }
      </style>
    `;

    document.head.insertAdjacentHTML('beforeend', style);
    document.body.insertAdjacentHTML('beforeend', html);

    modal = document.getElementById('memberApplyModal');
    form = document.getElementById('memberApplyForm');
    tip = document.getElementById('ma-tip');
    submitBtn = document.getElementById('ma-submit');
    groupSelect = document.getElementById('ma-groupName');
    captchaRow = document.getElementById('ma-captchaRow');
    captchaImage = document.getElementById('ma-captchaImage');
    captchaInput = document.getElementById('ma-captchaCode');
    captchaHint = document.getElementById('ma-captchaHint');
    var captchaRefresh = document.getElementById('ma-captchaRefresh');
    if (captchaRefresh) {
      captchaRefresh.addEventListener('click', function() { loadCaptcha(true); });
    }
  }

  function loadGroups() {
    if (groupsLoaded) return;
    fetch(API_BASE + '/membergroups', {
      credentials: 'omit',
      headers: { 'Accept': 'application/json' },
      cache: 'no-store'
    })
      .then(parseJsonResponse)
      .then(function(groups) {
        groupSelect.innerHTML = '';
        if (!groups || groups.length === 0) {
          groupSelect.innerHTML = '<option value="">暂无可申请的分组</option>';
          return;
        }
        groups.forEach(function(g) {
          var opt = document.createElement('option');
          opt.value = g.metadata.name;
          opt.textContent = g.spec.displayName || g.metadata.name;
          groupSelect.appendChild(opt);
        });
        groupsLoaded = true;
      })
      .catch(function(err) {
        groupSelect.innerHTML = '<option value="">分组加载失败</option>';
        showTip('分组列表加载失败：' + err.message, 'error');
      });
  }

  function setCaptchaHint(msg, type) {
    if (!captchaHint) return;
    captchaHint.className = 'member-apply-widget-form-hint' + (type ? ' is-' + type : '');
    captchaHint.textContent = msg;
  }

  function showCaptchaRow(visible) {
    if (!captchaRow || !captchaInput) return;
    captchaRow.hidden = !visible;
    captchaInput.required = !!visible;
    if (!visible) captchaInput.value = '';
  }

  // 验证码接口不依赖 Cookie：challengeId 由前端保存并随表单回传，服务端校验后立即失效。
  function loadCaptcha(isRefresh) {
    if (!captchaRow || !captchaInput || captchaLoading) return;
    captchaLoading = true;
    if (isRefresh) captchaInput.value = '';
    setCaptchaHint('正在获取验证码…');
    fetch(API_BASE + '/membersubmits/-/captcha', {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Accept': 'application/json' },
      cache: 'no-store'
    })
      .then(parseJsonResponse)
      .then(function(data) {
        captchaRequired = true;
        captchaChallengeId = (data && data.challengeId) || '';
        if (captchaImage) captchaImage.src = data.image;
        showCaptchaRow(true);
        setCaptchaHint('看不清？点击图片可以换一张。');
      })
      .catch(function(err) {
        captchaChallengeId = '';
        if (captchaImage) captchaImage.removeAttribute('src');
        if (err.status === 403 || err.status === 404 || err.status === 405) {
          // 站点未启用验证码，或服务端版本还没有该接口
          captchaRequired = false;
          showCaptchaRow(false);
          return;
        }
        captchaRequired = true;
        showCaptchaRow(true);
        setCaptchaHint('验证码获取失败：' + err.message + '，请点击图片重试。', 'error');
      })
      .finally(function() {
        captchaLoading = false;
      });
  }

  function showTip(msg, type) {
    if (!tip) return;
    tip.className = 'member-apply-widget-form-tip member-apply-widget-form-tip-' + type;
    tip.textContent = msg;
  }

  function submitForm() {
    if (!form || !form.checkValidity()) {
      if (form) form.reportValidity();
      return;
    }

    var payload = {
      displayName: form.displayName.value.trim(),
      email: form.email.value.trim(),
      school: form.school.value.trim(),
      qq: form.qq.value.trim(),
      qqFriendLink: form.qqFriendLink.value.trim() || null,
      groupName: form.groupName.value
    };

    if (captchaRequired) {
      payload.challengeId = captchaChallengeId;
      payload.captchaCode = captchaInput.value.trim();
    }

    submitBtn.disabled = true;
    submitBtn.textContent = '提交中...';
    tip.className = 'member-apply-widget-form-tip';
    tip.textContent = '';

    fetch(API_BASE + '/membersubmits/-/submit', {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then(parseJsonResponse)
      .then(function() {
        showTip('申请提交成功，等待管理员审核。', 'success');
        setTimeout(function() { window.MemberApplyWidget.close(); }, 1800);
      })
      .catch(function(err) {
        showTip('提交失败：' + err.message, 'error');
        if (/invalid-member-application-captcha$/.test(err.problemType || '')) {
          loadCaptcha(true);
        }
      })
      .finally(function() {
        submitBtn.disabled = false;
        submitBtn.textContent = '提交申请';
      });
  }

  function setGroupHint(msg, type) {
    var hint = document.getElementById('ma-groupHint');
    if (!hint) return;
    hint.className = 'member-apply-widget-form-hint' + (type ? ' is-' + type : '');
    hint.textContent = msg;
  }

  // 地区关键字映射
  var regionKeywords = {
    '北京': ['北京', 'Beijing'],
    '上海': ['上海', 'Shanghai'],
    '广东': ['广东', '广州', '深圳', '东莞', '佛山', '珠海', '汕头', 'Guangdong', 'Guangzhou', 'Shenzhen', 'Foshan', 'Zhuhai'],
    '江苏': ['江苏', '南京', '苏州', '无锡', '常州', 'Jiangsu', 'Nanjing', 'Suzhou', 'Wuxi', 'Changzhou'],
    '浙江': ['浙江', '杭州', '宁波', '温州', 'Zhejiang', 'Hangzhou', 'Ningbo', 'Wenzhou'],
    '山东': ['山东', '济南', '青岛', '曲阜', 'Shandong', 'Jinan', 'Qingdao', 'Qufu'],
    '四川': ['四川', '成都', 'Sichuan', 'Chengdu'],
    '湖北': ['湖北', '武汉', 'Hubei', 'Wuhan'],
    '湖南': ['湖南', '长沙', 'Hunan', 'Changsha'],
    '河南': ['河南', '郑州', 'Henan', 'Zhengzhou'],
    '河北': ['河北', '石家庄', 'Hebei', 'Shijiazhuang'],
    '福建': ['福建', '福州', '厦门', '泉州', 'Fujian', 'Fuzhou', 'Xiamen', 'Quanzhou'],
    '陕西': ['陕西', '西安', 'Shaanxi', 'Xi\'an'],
    '辽宁': ['辽宁', '沈阳', '大连', 'Liaoning', 'Shenyang', 'Dalian'],
    '黑龙江': ['黑龙江', '哈尔滨', 'Heilongjiang', 'Harbin'],
    '吉林': ['吉林', '长春', 'Jilin', 'Changchun'],
    '安徽': ['安徽', '合肥', 'Anhui', 'Hefei'],
    '江西': ['江西', '南昌', 'Jiangxi', 'Nanchang'],
    '重庆': ['重庆', 'Chongqing'],
    '天津': ['天津', 'Tianjin'],
    '云南': ['云南', '昆明', 'Yunnan', 'Kunming'],
    '贵州': ['贵州', '贵阳', 'Guizhou', 'Guiyang'],
    '广西': ['广西', '南宁', '桂林', 'Guangxi', 'Nanning', 'Guilin'],
    '海南': ['海南', '海口', 'Hainan', 'Haikou'],
    '内蒙古': ['内蒙古', '呼和浩特', 'Inner Mongolia', 'Hohhot'],
    '新疆': ['新疆', '乌鲁木齐', 'Xinjiang', 'Urumqi'],
    '西藏': ['西藏', '拉萨', 'Tibet', 'Lhasa'],
    '甘肃': ['甘肃', '兰州', 'Gansu', 'Lanzhou'],
    '青海': ['青海', '西宁', 'Qinghai', 'Xining'],
    '宁夏': ['宁夏', '银川', 'Ningxia', 'Yinchuan'],
    '山西': ['山西', '太原', 'Shanxi', 'Taiyuan'],
    '台湾': ['台湾', 'Taiwan'],
    '香港': ['香港', 'Hong Kong', 'HK'],
    '澳门': ['澳门', 'Macau', 'MO']
  };

  var lastSchoolValue = '';

  function autoSelectGroup() {
    if (!groupSelect || !groupSelect.options || groupSelect.options.length === 0) return;

    var region = null;
    var school = '';
    var name = '';

    var schoolInput = document.getElementById('ma-school');
    if (schoolInput) school = schoolInput.value.trim();

    var nameInput = document.getElementById('ma-displayName');
    if (nameInput) name = nameInput.value.trim();

    // 1. 尝试直接匹配分组名称（学校名或账号名直接匹配分组名）
    if (school || name) {
      for (var i = 0; i < groupSelect.options.length; i++) {
        var option = groupSelect.options[i];
        var gName = (option.textContent || option.value).toLowerCase();
        if (gName === '加载中...' || gName === '暂无可申请的分组') continue;

        if (school && (school.toLowerCase().indexOf(gName) !== -1 || gName.indexOf(school.toLowerCase()) !== -1)) {
          groupSelect.selectedIndex = i;
          setGroupHint('已自动选择分组：' + option.textContent, 'success');
          return;
        }
        if (name && (name.toLowerCase().indexOf(gName) !== -1 || gName.indexOf(name.toLowerCase()) !== -1)) {
          groupSelect.selectedIndex = i;
          setGroupHint('已自动选择分组：' + option.textContent, 'success');
          return;
        }
      }
    }

    // 2. 从学校名称判断地区
    if (!region && school) {
      region = detectRegion(school);
    }

    // 3. 从账号名称判断地区
    if (!region && name) {
      region = detectRegion(name);
    }

    // 4. 如果检测到地区，自动选择对应的分组
    if (region) {
      if (selectGroupByRegion(region)) return;
    }

    // 5. 如果都没匹配上，尝试选择"其他"、"全国"、"默认"或第一个有效分组
    for (var j = 0; j < groupSelect.options.length; j++) {
      var opt = groupSelect.options[j];
      var optName = (opt.textContent || opt.value).toLowerCase();
      if (optName.indexOf('其他') !== -1 || optName.indexOf('全国') !== -1 || optName.indexOf('默认') !== -1 || optName.indexOf('未知') !== -1) {
        groupSelect.selectedIndex = j;
        setGroupHint('已自动选择分组：' + opt.textContent, 'success');
        return;
      }
    }

    // 兜底：如果没选上，默认选第一个
    if (groupSelect.selectedIndex === -1 && groupSelect.options.length > 0) {
      groupSelect.selectedIndex = 0;
      setGroupHint('已自动选择分组：' + groupSelect.options[0].textContent, 'success');
    }
  }

  // 监听学校输入变化，自动选择分组
  function setupSchoolListener() {
    var schoolInput = document.getElementById('ma-school');
    if (!schoolInput) return;

    // 防抖，避免频繁触发
    var debounceTimer = null;
    schoolInput.addEventListener('input', function() {
      var value = this.value.trim();
      if (value === lastSchoolValue) return;
      lastSchoolValue = value;

      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(function() {
        if (value && value.length >= 2) {
          autoSelectGroup();
        }
      }, 500);
    });
  }

  function detectRegion(text) {
    text = text.toLowerCase();
    for (var regionName in regionKeywords) {
      var keywords = regionKeywords[regionName];
      for (var i = 0; i < keywords.length; i++) {
        if (text.indexOf(keywords[i].toLowerCase()) !== -1) {
          return regionName;
        }
      }
    }
    return null;
  }

  function selectGroupByRegion(region) {
    for (var i = 0; i < groupSelect.options.length; i++) {
      var option = groupSelect.options[i];
      var groupName = option.textContent || option.value;
      groupName = groupName.toLowerCase();
      var regionLower = region.toLowerCase();

      // 检查分组名是否包含地区名
      if (groupName.indexOf(regionLower) !== -1) {
        groupSelect.selectedIndex = i;
        setGroupHint('已自动选择分组：' + option.textContent, 'success');
        return true;
      }
    }
    return false;
  }

  // ===== 二维码解析（上传图片自动识别 QQ 好友链接） =====
  var jsQrPromise = null;

  function loadJsQR(callback) {
    if (window.jsQR) { callback(window.jsQR); return; }
    if (!jsQrPromise) {
      jsQrPromise = new Promise(function(resolve) {
        var s = document.createElement('script');
        s.src = new URL('vendor/jsQR.js', WIDGET_ASSET_BASE).href;
        s.dataset.memberJsqr = 'true';
        s.onload = function() { resolve(window.jsQR || null); };
        s.onerror = function() {
          jsQrPromise = null;
          resolve(null);
        };
        document.head.appendChild(s);
      });
    }
    jsQrPromise.then(callback);
  }

  function parseQrCode(input) {
    var file = input.files && input.files[0];
    if (!file) return;
    var hint = document.getElementById('ma-qrHint');
    var linkInput = document.getElementById('ma-qqFriendLink');
    if (hint) {
      hint.className = 'member-apply-widget-form-hint';
      hint.textContent = '正在解析二维码...';
    }

    loadJsQR(function(jsQR) {
      if (!jsQR) {
        if (hint) {
          hint.className = 'member-apply-widget-form-hint is-error';
          hint.textContent = '二维码解析库加载失败，请手动填写好友链接。';
        }
        input.value = '';
        return;
      }

      var reader = new FileReader();
      reader.onload = function(e) {
        var img = new Image();
        img.onload = function() {
          var canvas = document.createElement('canvas');
          var ctx = canvas.getContext('2d');
          canvas.width = img.width;
          canvas.height = img.height;
          ctx.drawImage(img, 0, 0);
          var imageData = ctx.getImageData(0, 0, img.width, img.height);
          var code = jsQR(imageData.data, imageData.width, imageData.height);
          if (code && code.data) {
            if (linkInput) linkInput.value = code.data;
            if (hint) {
              hint.className = 'member-apply-widget-form-hint is-success';
              hint.textContent = '已解析二维码并填入好友链接。';
            }
          } else {
            if (hint) {
              hint.className = 'member-apply-widget-form-hint is-error';
              hint.textContent = '未识别到二维码内容，请确认图片是否为 QQ 二维码。';
            }
          }
          input.value = '';
        };
        img.src = e.target.result;
      };
      reader.readAsDataURL(file);
    });
  }

  window.MemberApplyWidget = {
    open: function() {
      if (!modal) createModal();
      if (form) form.reset();
      if (tip) {
        tip.className = 'member-apply-widget-form-tip';
        tip.textContent = '';
      }
      setGroupHint('', '');
      var qrHint = document.getElementById('ma-qrHint');
      if (qrHint) {
        qrHint.className = 'member-apply-widget-form-hint';
        qrHint.textContent = '支持上传 QQ 二维码图片，自动解析好友链接并填入。';
      }
      captchaChallengeId = '';
      captchaRequired = false;
      showCaptchaRow(false);
      modal.classList.add('is-open');
      document.body.style.overflow = 'hidden';
      loadGroups();
      loadCaptcha(false);
      setupSchoolListener(); // 设置学校输入监听
    },

    close: function() {
      if (!modal) return;
      modal.classList.remove('is-open');
      document.body.style.overflow = '';
    },

    submit: function() {
      submitForm();
    },

    parseQrCode: parseQrCode,
    autoSelectGroup: autoSelectGroup
  };

  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape' && modal && modal.classList.contains('is-open')) {
      window.MemberApplyWidget.close();
    }
  });

})(window);
