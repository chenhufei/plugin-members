/**
 * Member Apply Widget - 成员申请弹窗组件
 * 使用方式: MemberApplyWidget.open()
 *
 * 依赖插件: PluginMembers
 * API 端点: /apis/anonymous.member.plugin.halo.run/v1alpha1
 */

(function(window) {
  'use strict';

  const API_BASE = '/apis/anonymous.member.plugin.halo.run/v1alpha1';

  let modal = null;
  let form = null;
  let tip = null;
  let submitBtn = null;
  let groupSelect = null;
  let groupsLoaded = false;

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
              <div class="member-apply-widget-qq-row">
                <input class="member-apply-widget-form-input" id="ma-qq" name="qq" type="text" required pattern="\\d{5,12}" placeholder="5-12 位数字，填写后自动获取昵称" />
                <button type="button" class="member-apply-widget-btn-mini" id="ma-fetchQq" onclick="MemberApplyWidget.fetchQqInfo()">获取信息</button>
              </div>
              <div class="member-apply-widget-form-hint" id="ma-qqHint">填写 QQ 号后点击「获取信息」，自动填充昵称与 QQ 邮箱。</div>
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-displayName">账号名称 <span class="member-apply-widget-form-req">*</span></label>
              <input class="member-apply-widget-form-input" id="ma-displayName" name="displayName" type="text" required maxlength="50" placeholder="2-50 字符" />
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-email">邮箱 <span class="member-apply-widget-form-req">*</span></label>
              <div class="member-apply-widget-qq-row">
                <input class="member-apply-widget-form-input" id="ma-email" name="email" type="email" required placeholder="用于审核通知" />
                <button type="button" class="member-apply-widget-btn-mini" id="ma-qqEmailBtn" onclick="MemberApplyWidget.fillQqEmail()">QQ邮箱</button>
              </div>
            </div>
            <div class="member-apply-widget-form-row">
              <label class="member-apply-widget-form-label" for="ma-school">学校 <span class="member-apply-widget-form-req">*</span></label>
              <input class="member-apply-widget-form-input" id="ma-school" name="school" type="text" required placeholder="所在学校全称" />
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
  }

  function loadGroups() {
    if (groupsLoaded) return;
    fetch(API_BASE + '/membergroups')
      .then(function(r) { return r.json(); })
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

    submitBtn.disabled = true;
    submitBtn.textContent = '提交中...';
    tip.className = 'member-apply-widget-form-tip';
    tip.textContent = '';

    fetch(API_BASE + '/membersubmits/-/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then(function(r) {
        if (!r.ok) return r.json().then(function(e) { throw new Error(e.message || ('HTTP ' + r.status)); });
        return r.json();
      })
      .then(function() {
        showTip('申请提交成功，等待管理员审核。', 'success');
        setTimeout(function() { window.MemberApplyWidget.close(); }, 1800);
      })
      .catch(function(err) {
        showTip('提交失败：' + err.message, 'error');
      })
      .finally(function() {
        submitBtn.disabled = false;
        submitBtn.textContent = '提交申请';
      });
  }

  // ===== QQ 号获取昵称/邮箱 =====
  // 通过 JSONP 调用 QQ 官方公开接口获取昵称；邮箱直接拼接 {qq}@qq.com
  function setQqHint(msg, type) {
    var hint = document.getElementById('ma-qqHint');
    if (!hint) return;
    hint.className = 'member-apply-widget-form-hint' + (type ? ' is-' + type : '');
    hint.textContent = msg;
  }

  function fetchQqInfo() {
    var qqInput = document.getElementById('ma-qq');
    var fetchBtn = document.getElementById('ma-fetchQq');
    if (!qqInput || !fetchBtn) return;
    var qq = qqInput.value.trim();
    if (!/^\d{5,12}$/.test(qq)) {
      setQqHint('请先填写有效的 QQ 号（5-12 位数字）。', 'error');
      return;
    }

    fetchBtn.disabled = true;
    fetchBtn.textContent = '获取中...';
    setQqHint('正在获取 QQ 昵称...', '');

    var done = false;
    function cleanup(err, okHint, errHint) {
      if (done) return;
      done = true;
      fetchBtn.disabled = false;
      fetchBtn.textContent = '获取信息';
      if (okHint) setQqHint(okHint, 'success');
      else if (errHint) setQqHint(errHint, 'error');
    }

    // 通过插件后端代理获取 QQ 昵称，规避浏览器直接请求第三方接口的跨域(CORS)与 403 限制。
    // 后端端点: GET /apis/anonymous.member.plugin.halo.run/v1alpha1/qq-info?qq=
    // 归一化响应: { qq, nickname, avatar, email }
    var abortCtrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    var timeoutId = setTimeout(function() {
      if (abortCtrl) { try { abortCtrl.abort(); } catch (_) {} }
      cleanup(null, null, '昵称获取超时，请手动填写或稍后重试。');
    }, 8000);

    try {
      var url = API_BASE + '/qq-info?qq=' + encodeURIComponent(qq);
      var fetchOpts = {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store'
      };
      if (abortCtrl) fetchOpts.signal = abortCtrl.signal;

      fetch(url, fetchOpts)
        .then(function(res) {
          return res.text().then(function(txt) {
            var data = null;
            try { data = txt ? JSON.parse(txt) : null; } catch (_) { data = { raw: txt }; }
            return { ok: res.ok, status: res.status, data: data };
          });
        })
        .then(function(res) {
          try {
            clearTimeout(timeoutId);
          } catch (_) {}

          // 后端归一化响应，昵称直接取 data.nickname
          var d = (res && res.data && typeof res.data === 'object') ? res.data : null;
          var nickname = (d && typeof d.nickname === 'string') ? d.nickname : null;

          if (nickname && typeof nickname === 'string') {
            nickname = nickname.trim();
            if (nickname.length > 0) {
              var nameInput = document.getElementById('ma-displayName');
              // 强制更新账号名称，即使已有值
              if (nameInput) {
                nameInput.value = nickname;
              }
              cleanup(null, '已获取 QQ 昵称，可点击「QQ邮箱」按钮自动填充邮箱。', null);
              return;
            }
          }
          // 未获取到昵称
          var reason = (d && (d.message || d.msg)) ? '：' + (d.message || d.msg) : '';
          cleanup(null, null, '未获取到昵称' + reason + '，请手动填写。');
        })
        .catch(function(err) {
          try { clearTimeout(timeoutId); } catch (_) {}
          cleanup(null, null, '获取信息失败（网络限制），请手动填写昵称。');
        });
    } catch (e) {
      try { clearTimeout(timeoutId); } catch (_) {}
      cleanup(null, null, '获取信息失败，请手动填写昵称。');
    }
  }

  function fillQqEmail() {
    var qqInput = document.getElementById('ma-qq');
    var emailInput = document.getElementById('ma-email');
    if (!qqInput || !emailInput) return;
    var qq = qqInput.value.trim();
    if (!/^\d{5,12}$/.test(qq)) {
      setQqHint('请先填写有效的 QQ 号（5-12 位数字）。', 'error');
      return;
    }
    emailInput.value = qq + '@qq.com';
    setQqHint('已填入 QQ 邮箱。', 'success');
  }

  // ===== 二维码解析（上传图片自动识别 QQ 好友链接） =====
  function loadJsQR(callback) {
    if (window.jsQR) { callback(window.jsQR); return; }
    var s = document.createElement('script');
    s.src = 'https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.js';
    s.onload = function() { callback(window.jsQR); };
    s.onerror = function() { callback(null); };
    document.head.appendChild(s);
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
      // 重置 QQ 提示
      var qqHint = document.getElementById('ma-qqHint');
      if (qqHint) {
        qqHint.className = 'member-apply-widget-form-hint';
        qqHint.textContent = '填写 QQ 号后点击「获取信息」，自动获取昵称。';
      }
      var qrHint = document.getElementById('ma-qrHint');
      if (qrHint) {
        qrHint.className = 'member-apply-widget-form-hint';
        qrHint.textContent = '支持上传 QQ 二维码图片，自动解析好友链接并填入。';
      }
      modal.classList.add('is-open');
      document.body.style.overflow = 'hidden';
      loadGroups();
    },

    close: function() {
      if (!modal) return;
      modal.classList.remove('is-open');
      document.body.style.overflow = '';
    },

    submit: function() {
      submitForm();
    },

    fetchQqInfo: fetchQqInfo,
    parseQrCode: parseQrCode,
    fillQqEmail: fillQqEmail
  };

  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape' && modal && modal.classList.contains('is-open')) {
      window.MemberApplyWidget.close();
    }
  });

})(window);