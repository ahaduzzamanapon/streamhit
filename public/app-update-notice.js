(function() {
    // Avoid running on admin page
    if (window.location.pathname.startsWith("/admin")) return;

    function renderUpdateNotice() {
        if (document.getElementById("nxlyAppUpdateModalOverlay")) return;

        // Inject CSS
        const style = document.createElement("style");
        style.id = "nxlyAppUpdateNoticeStyles";
        style.textContent = `
            #nxlyAppUpdateModalOverlay {
                position: fixed;
                top: 0;
                left: 0;
                width: 100vw;
                height: 100vh;
                background: rgba(3, 7, 18, 0.85);
                backdrop-filter: blur(12px);
                -webkit-backdrop-filter: blur(12px);
                z-index: 9999999;
                display: flex;
                align-items: center;
                justify-content: center;
                padding: 16px;
                opacity: 0;
                visibility: hidden;
                transition: opacity 0.3s cubic-bezier(0.16, 1, 0.3, 1), visibility 0.3s;
                font-family: 'Outfit', 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, sans-serif;
            }
            #nxlyAppUpdateModalOverlay.active {
                opacity: 1;
                visibility: visible;
            }
            .nxly-update-card {
                background: linear-gradient(145deg, rgba(15, 23, 42, 0.95), rgba(7, 11, 20, 0.98));
                border: 1px solid rgba(16, 185, 129, 0.35);
                box-shadow: 0 0 50px rgba(16, 185, 129, 0.15), 0 25px 50px -12px rgba(0, 0, 0, 0.85);
                border-radius: 24px;
                max-width: 480px;
                width: 100%;
                padding: 28px 24px 24px;
                text-align: center;
                position: relative;
                transform: scale(0.92) translateY(15px);
                transition: transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
                color: #ffffff;
            }
            #nxlyAppUpdateModalOverlay.active .nxly-update-card {
                transform: scale(1) translateY(0);
            }
            .nxly-update-badge {
                display: inline-flex;
                align-items: center;
                gap: 6px;
                background: linear-gradient(135deg, rgba(16, 185, 129, 0.2), rgba(6, 182, 212, 0.2));
                border: 1px solid rgba(16, 185, 129, 0.4);
                color: #10b981;
                font-size: 11.5px;
                font-weight: 700;
                letter-spacing: 0.5px;
                text-transform: uppercase;
                padding: 5px 14px;
                border-radius: 999px;
                margin-bottom: 16px;
            }
            .nxly-update-badge span.pulse-dot {
                width: 7px;
                height: 7px;
                background-color: #10b981;
                border-radius: 50%;
                box-shadow: 0 0 8px #10b981;
                animation: nxlyPulse 1.5s infinite;
            }
            @keyframes nxlyPulse {
                0% { transform: scale(0.95); opacity: 0.8; }
                50% { transform: scale(1.4); opacity: 1; }
                100% { transform: scale(0.95); opacity: 0.8; }
            }
            .nxly-update-icon-wrap {
                width: 64px;
                height: 64px;
                border-radius: 20px;
                background: linear-gradient(135deg, #10b981, #06b6d4);
                display: flex;
                align-items: center;
                justify-content: center;
                margin: 0 auto 16px;
                box-shadow: 0 10px 25px rgba(16, 185, 129, 0.35);
                font-size: 28px;
            }
            .nxly-update-title {
                font-size: 22px;
                font-weight: 800;
                line-height: 1.3;
                margin-bottom: 8px;
                background: linear-gradient(135deg, #ffffff 40%, #a7f3d0 100%);
                -webkit-background-clip: text;
                -webkit-text-fill-color: transparent;
            }
            .nxly-update-subtitle {
                font-size: 13.5px;
                color: #94a3b8;
                line-height: 1.5;
                margin-bottom: 18px;
            }
            .nxly-update-features {
                background: rgba(255, 255, 255, 0.03);
                border: 1px solid rgba(255, 255, 255, 0.06);
                border-radius: 14px;
                padding: 12px 14px;
                text-align: left;
                margin-bottom: 22px;
                font-size: 12.5px;
                color: #cbd5e1;
                line-height: 1.6;
            }
            .nxly-update-features-item {
                display: flex;
                align-items: flex-start;
                gap: 8px;
                margin-bottom: 6px;
            }
            .nxly-update-features-item:last-child {
                margin-bottom: 0;
            }
            .nxly-update-features-item i {
                color: #10b981;
                margin-top: 3px;
                font-size: 13px;
            }
            .nxly-btn-primary {
                display: flex;
                align-items: center;
                justify-content: center;
                gap: 10px;
                width: 100%;
                background: linear-gradient(135deg, #10b981, #059669);
                color: #022c22 !important;
                font-weight: 800;
                font-size: 15px;
                padding: 14px 20px;
                border-radius: 14px;
                text-decoration: none;
                transition: transform 0.2s, box-shadow 0.2s;
                box-shadow: 0 10px 20px rgba(16, 185, 129, 0.3);
                border: none;
                cursor: pointer;
            }
            .nxly-btn-primary:hover {
                transform: translateY(-2px);
                box-shadow: 0 14px 26px rgba(16, 185, 129, 0.45);
                color: #022c22 !important;
            }
            .nxly-btn-dismiss {
                margin-top: 10px;
                background: transparent;
                border: none;
                color: #64748b;
                font-size: 12.5px;
                font-weight: 600;
                cursor: pointer;
                padding: 8px 16px;
                transition: color 0.2s;
            }
            .nxly-btn-dismiss:hover {
                color: #cbd5e1;
            }

            /* Persistent Floating Pill when closed */
            #nxlyFloatingNoticePill {
                position: fixed;
                bottom: 24px;
                left: 50%;
                transform: translateX(-50%) translateY(120px);
                z-index: 9999998;
                background: rgba(15, 23, 42, 0.94);
                border: 1px solid rgba(16, 185, 129, 0.4);
                backdrop-filter: blur(10px);
                -webkit-backdrop-filter: blur(10px);
                box-shadow: 0 8px 30px rgba(0, 0, 0, 0.7);
                border-radius: 999px;
                padding: 8px 18px 8px 14px;
                display: flex;
                align-items: center;
                gap: 12px;
                color: #fff;
                font-size: 13px;
                font-weight: 600;
                cursor: pointer;
                transition: transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
                white-space: nowrap;
            }
            #nxlyFloatingNoticePill.visible {
                transform: translateX(-50%) translateY(0);
            }
            #nxlyFloatingNoticePill:hover {
                border-color: #10b981;
                box-shadow: 0 8px 30px rgba(16, 185, 129, 0.25);
            }
            @media (max-width: 600px) {
                #nxlyFloatingNoticePill {
                    bottom: 74px; /* Above mobile bottom nav */
                    width: 90%;
                    justify-content: space-between;
                    font-size: 12px;
                    padding: 8px 14px;
                }
            }
        `;
        document.head.appendChild(style);

        // Modal Overlay DOM
        const overlay = document.createElement("div");
        overlay.id = "nxlyAppUpdateModalOverlay";
        overlay.innerHTML = `
            <div class="nxly-update-card">
                <div class="nxly-update-badge">
                    <span class="pulse-dot"></span>
                    নতুন ভার্সন রিলিজ (v1.0.20)
                </div>
                <div class="nxly-update-icon-wrap">
                    <i class="fa-solid fa-cloud-arrow-down" style="color: #022c22;"></i>
                </div>
                <div class="nxly-update-title">অ্যাপের সমস্যা সমাধান হয়েছে!</div>
                <div class="nxly-update-subtitle">
                    অ্যাপে আপডেট চাওয়ার সমস্যা ও টপ বার গ্যাপ ঠিক করা হয়েছে। নিরবচ্ছিন্ন স্ট্রিমিং করতে এখনই নতুন ভার্সনে আপডেট করে নিন।
                </div>
                <div class="nxly-update-features">
                    <div class="nxly-update-features-item">
                        <i class="fa-solid fa-circle-check"></i>
                        <span><strong>আপডেট লুপ বন্ধ:</strong> বারবার আপডেট চাওয়ার সমস্যা ঠিক করা হয়েছে।</span>
                    </div>
                    <div class="nxly-update-features-item">
                        <i class="fa-solid fa-circle-check"></i>
                        <span><strong>টপ বার ফিক্স:</strong> স্ট্যাটাস বারের নিচে থাকা ফাঁকা জায়গা রিমুভ করা হয়েছে।</span>
                    </div>
                    <div class="nxly-update-features-item">
                        <i class="fa-solid fa-circle-check"></i>
                        <span><strong>পুশ নোটিফিকেশন:</strong> নতুন সিনেমা ও পর্বের নোটিফিকেশন সচল।</span>
                    </div>
                </div>
                <a href="/uploads/apks/NXLY-Stream.apk" download class="nxly-btn-primary" id="nxlyUpdateNowBtn">
                    <i class="fa-solid fa-download"></i>
                    এখনই আপডেট করুন (APK Download)
                </a>
                <button class="nxly-btn-dismiss" id="nxlyDismissUpdateModal">পরে করব (Dismiss)</button>
            </div>
        `;
        document.body.appendChild(overlay);

        // Floating pill for persistent access
        const pill = document.createElement("div");
        pill.id = "nxlyFloatingNoticePill";
        pill.innerHTML = `
            <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#10b981; box-shadow:0 0 8px #10b981;"></span>
            <span>অ্যাপের সমস্যা ঠিক হয়েছে (v1.0.20)</span>
            <a href="/uploads/apks/NXLY-Stream.apk" download style="background: #10b981; color: #022c22; padding: 4px 12px; border-radius: 999px; text-decoration: none; font-size: 12px; font-weight: 700;">আপডেট নিন</a>
        `;
        document.body.appendChild(pill);

        // Events
        const dismissBtn = document.getElementById("nxlyDismissUpdateModal");
        const downloadBtn = document.getElementById("nxlyUpdateNowBtn");

        function closeModal() {
            overlay.classList.remove("active");
            setTimeout(() => {
                pill.classList.add("visible");
            }, 300);
            try { sessionStorage.setItem("nxly_update_pop_dismissed", "1"); } catch (_) {}
        }

        if (dismissBtn) dismissBtn.addEventListener("click", closeModal);
        if (downloadBtn) downloadBtn.addEventListener("click", function() {
            setTimeout(closeModal, 1000);
        });

        // Trigger popup
        const wasDismissed = sessionStorage.getItem("nxly_update_pop_dismissed");
        if (!wasDismissed) {
            setTimeout(() => {
                overlay.classList.add("active");
            }, 600);
        } else {
            pill.classList.add("visible");
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", renderUpdateNotice);
    } else {
        renderUpdateNotice();
    }
})();
