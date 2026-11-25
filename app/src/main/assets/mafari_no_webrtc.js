/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

(function () {
  var names = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection',
    'RTCSessionDescription', 'RTCIceCandidate', 'RTCDataChannel', 'RTCDataChannelEvent',
    'RTCPeerConnectionIceEvent', 'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver',
    'RTCDtlsTransport', 'RTCIceTransport', 'RTCSctpTransport', 'RTCCertificate'];
  names.forEach(function (n) {
    try { delete window[n]; } catch (e) {}
    try {
      if (n in window) Object.defineProperty(window, n, { value: undefined, configurable: true, writable: true });
    } catch (e) {}
  });
  // 旧式 / 现代取流入口也一并拒绝，避免被用来建立 WebRTC 会话
  try {
    var deny = function () {
      return Promise.reject(new DOMException('Permission denied', 'NotAllowedError'));
    };
    if (navigator.mediaDevices) {
      ['getUserMedia', 'getDisplayMedia'].forEach(function (m) {
        try { navigator.mediaDevices[m] = deny; } catch (e) {}
      });
    }
  } catch (e) {}
})();
