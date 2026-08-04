// Arya's VRM avatar scene. Exposes window.AryaAvatar for Kotlin (VrmAvatarView.kt) to
// drive via WebView.evaluateJavascript(...):
//   AryaAvatar.setExpression("happy")   // one of EMOTION_TO_VRM's keys below
//   AryaAvatar.setMouthOpen(0.0..1.0)   // called ~30-60x/sec while ElevenLabs audio plays
//                                       // (real waveform amplitude — see VoiceHelper.attachVisualizer)
//
// Reports back to Android (if the "AndroidBridge" JS interface was injected — see
// VrmAvatarView.kt) via AndroidBridge.onModelLoaded() / AndroidBridge.onLoadError(message).

(function () {
  const holder = document.getElementById('canvas-holder');

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(28, window.innerWidth / window.innerHeight, 0.1, 20);
  camera.position.set(0, 1.38, 1.1); // roughly face/upper-chest framing for a standard VRM humanoid rig

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  holder.appendChild(renderer.domElement);

  const keyLight = new THREE.DirectionalLight(0xffffff, 1.0);
  keyLight.position.set(0.5, 1.5, 1.5);
  scene.add(keyLight);
  scene.add(new THREE.AmbientLight(0xffffff, 0.65));

  window.addEventListener('resize', () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  // Our fixed 8-tag emotion vocabulary (see relay's EMOTION_TAGS / AvatarEmotion.kt) mapped
  // onto VRM's standard expression presets. VRM only ships 6 core emotion presets, so
  // "caring"/"playful"/"serious" are approximated as blends of the nearest preset(s) rather
  // than 1:1 — still visibly distinct from plain happy/neutral.
  const EMOTION_TO_VRM = {
    neutral: { neutral: 1.0 },
    happy: { happy: 1.0 },
    sad: { sad: 1.0 },
    angry: { angry: 1.0 },
    surprised: { surprised: 1.0 },
    caring: { relaxed: 0.8, happy: 0.2 },
    playful: { happy: 0.7, surprised: 0.3 },
    serious: { neutral: 0.85, angry: 0.15 },
  };
  const ALL_EMOTION_PRESETS = ['neutral', 'happy', 'sad', 'angry', 'surprised', 'relaxed'];

  let currentVrm = null;
  let targetExpressionWeights = EMOTION_TO_VRM.neutral;
  let targetMouthOpen = 0;
  let smoothedMouthOpen = 0;
  const clock = new THREE.Clock();

  // Idle blink loop — irregular interval so it doesn't read as robotic (same reasoning as
  // the Canvas fallback face in LiveConversationScreen.kt).
  let blinkWeight = 0;
  let nextBlinkAt = 2 + Math.random() * 2.5;
  let blinkTimer = 0;

  function loadModel() {
    const loader = new THREE.GLTFLoader();
    loader.register((parser) => new THREE_VRM.VRMLoaderPlugin(parser));
    loader.load(
      'model.vrm',
      (gltf) => {
        const vrm = gltf.userData.vrm;
        if (currentVrm) scene.remove(currentVrm.scene);
        currentVrm = vrm;
        scene.add(vrm.scene);
        THREE_VRM.VRMUtils.rotateVRM0(vrm); // no-op for VRM1 models, fixes VRM0's backwards-facing default
        if (window.AndroidBridge && window.AndroidBridge.onModelLoaded) {
          window.AndroidBridge.onModelLoaded();
        }
      },
      undefined,
      (error) => {
        if (window.AndroidBridge && window.AndroidBridge.onLoadError) {
          window.AndroidBridge.onLoadError(String(error && error.message ? error.message : error));
        }
      }
    );
  }

  function animate() {
    requestAnimationFrame(animate);
    const dt = clock.getDelta();

    if (currentVrm) {
      const expr = currentVrm.expressionManager;
      if (expr) {
        // Smoothly blend toward the target emotion instead of snapping — a hard cut between
        // expressions every reply reads as glitchy, not alive.
        for (const preset of ALL_EMOTION_PRESETS) {
          const target = targetExpressionWeights[preset] || 0;
          const cur = expr.getValue(preset) || 0;
          expr.setValue(preset, cur + (target - cur) * Math.min(1, dt * 6));
        }

        // Blink, independent of the emotion blend above.
        blinkTimer += dt;
        if (blinkTimer >= nextBlinkAt) {
          blinkTimer = 0;
          nextBlinkAt = 2 + Math.random() * 2.5;
          blinkWeight = 1;
        }
        blinkWeight = Math.max(0, blinkWeight - dt * 8);
        expr.setValue('blink', blinkWeight);

        // Mouth: smoothed toward the latest real audio-amplitude sample from Kotlin so it
        // doesn't visually jitter frame-to-frame, but still tracks speech closely (see
        // VoiceHelper.kt's Visualizer -> setMouthOpen bridge).
        smoothedMouthOpen += (targetMouthOpen - smoothedMouthOpen) * Math.min(1, dt * 18);
        expr.setValue('aa', smoothedMouthOpen);
      }

      // Gentle idle breathing bob so the model doesn't look frozen between blinks/talking.
      currentVrm.scene.position.y = Math.sin(clock.elapsedTime * 1.2) * 0.006;

      currentVrm.update(dt);
    }

    renderer.render(scene, camera);
  }

  window.AryaAvatar = {
    setExpression: function (emotion) {
      targetExpressionWeights = EMOTION_TO_VRM[emotion] || EMOTION_TO_VRM.neutral;
    },
    setMouthOpen: function (level) {
      targetMouthOpen = Math.max(0, Math.min(1, level));
    },
  };

  loadModel();
  animate();
})();
