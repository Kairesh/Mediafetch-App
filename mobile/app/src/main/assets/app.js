// MediaFetch Pro Controller
(function () {
  'use strict';

  let currentMedia = null;
  let activeTab = 'video';
  let isDarkMode = true;
  let allTasks = [];
  let trimStartSec = 10;
  let trimEndSec = 30;

  // DOM Elements
  const html = document.documentElement;
  const themeToggleBtn = document.getElementById('themeToggleBtn');
  const themeIconDark = document.getElementById('themeIconDark');
  const themeIconLight = document.getElementById('themeIconLight');
  const downloadsDrawerBtn = document.getElementById('downloadsDrawerBtn');
  const downloadsDrawer = document.getElementById('downloadsDrawer');
  const closeDrawerBtn = document.getElementById('closeDrawerBtn');
  const activeDownloadsCount = document.getElementById('activeDownloadsCount');
  const drawerItemsCount = document.getElementById('drawerItemsCount');

  const incomingShareBanner = document.getElementById('incomingShareBanner');
  const shareBannerText = document.getElementById('shareBannerText');
  const dismissShareBtn = document.getElementById('dismissShareBtn');

  const urlInput = document.getElementById('urlInput');
  const pasteUrlBtn = document.getElementById('pasteUrlBtn');
  const clearUrlBtn = document.getElementById('clearUrlBtn');
  const fetchBtn = document.getElementById('fetchBtn');
  const loadingCard = document.getElementById('loadingCard');
  const mediaWorkspace = document.getElementById('mediaWorkspace');

  const mediaThumbnail = document.getElementById('mediaThumbnail');
  const mediaDurationBadge = document.getElementById('mediaDurationBadge');
  const mediaPlatformBadge = document.getElementById('mediaPlatformBadge');
  const mediaTitle = document.getElementById('mediaTitle');
  const mediaAuthor = document.getElementById('mediaAuthor');

  const segmentBtns = document.querySelectorAll('.seg-tab');
  const tabVideo = document.getElementById('tabVideo');
  const tabAudio = document.getElementById('tabAudio');
  const tabTrimmer = document.getElementById('tabTrimmer');
  const tabImages = document.getElementById('tabImages');
  const imageTabBtn = document.getElementById('imageTabBtn');
  const chapterTabBtn = document.getElementById('chapterTabBtn');
  const tabChapters = document.getElementById('tabChapters');
  const chaptersCountBadge = document.getElementById('chaptersCountBadge');
  const downloadAllChaptersBtn = document.getElementById('downloadAllChaptersBtn');
  const chaptersList = document.getElementById('chaptersList');

  const subtitleSelectorCard = document.getElementById('subtitleSelectorCard');
  const subtitleTrackSelect = document.getElementById('subtitleTrackSelect');

  const videoQualitiesList = document.getElementById('videoQualitiesList');
  const audioQualitiesList = document.getElementById('audioQualitiesList');
  const imageQualitiesList = document.getElementById('imageQualitiesList');

  // Trimmer
  const trimStartInput = document.getElementById('trimStartInput');
  const trimEndInput = document.getElementById('trimEndInput');
  const resetTrimBtn = document.getElementById('resetTrimBtn');
  const sliderFill = document.getElementById('sliderFill');
  const sliderMinLabel = document.getElementById('sliderMinLabel');
  const sliderSelectedLabel = document.getElementById('sliderSelectedLabel');
  const sliderMaxLabel = document.getElementById('sliderMaxLabel');
  const downloadTrimmedBtn = document.getElementById('downloadTrimmedBtn');
  const trimDownloadBtnText = document.getElementById('trimDownloadBtnText');
  const trimTypeVideo = document.getElementById('trimTypeVideo');
  const trimTypeAudio = document.getElementById('trimTypeAudio');
  const trimQualitiesGrid = document.getElementById('trimQualitiesGrid');
  let trimMediaType = 'video';
  let selectedTrimQualityId = '1080p';

  // Playlist & Batch
  let currentCustomFolder = null;
  const playlistSection = document.getElementById('playlistSection');
  const playlistItemsList = document.getElementById('playlistItemsList');
  const playlistCountBadge = document.getElementById('playlistCountBadge');
  const selectAllBtn = document.getElementById('selectAllBtn');
  const deselectAllBtn = document.getElementById('deselectAllBtn');
  const downloadBatchBtn = document.getElementById('downloadBatchBtn');
  const downloadBatchBtnText = document.getElementById('downloadBatchBtnText');
  const playlistZipToggle = document.getElementById('playlistZipToggle');
  const playlistSavePath = document.getElementById('playlistSavePath');
  const playlistChangeFolderBtn = document.getElementById('playlistChangeFolderBtn');

  // Task List
  const downloadsTaskList = document.getElementById('downloadsTaskList');
  const emptyDownloadsState = document.getElementById('emptyDownloadsState');
  const clearAllHistoryBtn = document.getElementById('clearAllHistoryBtn');

  // Rename Inputs
  const renameInput = document.getElementById('renameInput');
  const resetRenameBtn = document.getElementById('resetRenameBtn');
  const trimRenameInput = document.getElementById('trimRenameInput');

  // Carousel Multi-Slide Elements
  const carouselSection = document.getElementById('carouselSection');
  const carouselSlidesTrack = document.getElementById('carouselSlidesTrack');
  const carouselCountBadge = document.getElementById('carouselCountBadge');
  const carouselSelectedCountBadge = document.getElementById('carouselSelectedCountBadge');
  const carouselSelectAllBtn = document.getElementById('carouselSelectAllBtn');
  const carouselDeselectBtn = document.getElementById('carouselDeselectBtn');
  const carouselZipToggle = document.getElementById('carouselZipToggle');
  const carouselSavePath = document.getElementById('carouselSavePath');
  const carouselChangeFolderBtn = document.getElementById('carouselChangeFolderBtn');
  const downloadSelectedSlidesBtn = document.getElementById('downloadSelectedSlidesBtn');
  const downloadSelectedSlidesBtnText = document.getElementById('downloadSelectedSlidesBtnText');
  const downloadAllSlidesBtn = document.getElementById('downloadAllSlidesBtn');
  let carouselSlidesData = [];
  let selectedSlideIndices = new Set();
  let currentCarouselFilter = 'all';

  // Playlist Quality
  let selectedBatchQuality = '1080p';

  // Set Insets from Android Notch
  window.setSystemInsets = function (topDp, bottomDp) {
    const topSafe = Math.max(38, topDp);
    document.documentElement.style.setProperty('--safe-top', topSafe + 'px');
    document.documentElement.style.setProperty('--safe-bottom', Math.max(20, bottomDp) + 'px');
  };

  const CURATED_THEMES = {
    sunset: {
      id: 'sunset',
      name: 'Sunset Flare',
      primary: '#FF9500',
      secondary: '#FF453A',
      bg: '#000000',
      surface: '#141416',
      card: 'rgba(26, 24, 24, 0.88)'
    },
    blue: {
      id: 'blue',
      name: 'Cyber Blue',
      primary: '#0A84FF',
      secondary: '#0060DF',
      bg: '#000000',
      surface: '#0C0C0E',
      card: 'rgba(22, 22, 26, 0.85)'
    },
    purple: {
      id: 'purple',
      name: 'Cyberpunk',
      primary: '#BF5AF2',
      secondary: '#FF2D55',
      bg: '#080811',
      surface: '#120F1F',
      card: 'rgba(23, 20, 36, 0.88)'
    },
    green: {
      id: 'green',
      name: 'Emerald Matrix',
      primary: '#30D158',
      secondary: '#059669',
      bg: '#000000',
      surface: '#0D1410',
      card: 'rgba(16, 24, 19, 0.88)'
    },
    gold: {
      id: 'gold',
      name: 'Solar Gold',
      primary: '#FFD60A',
      secondary: '#D97706',
      bg: '#0D0D0E',
      surface: '#171612',
      card: 'rgba(28, 26, 20, 0.88)'
    },
    red: {
      id: 'red',
      name: 'Crimson Fury',
      primary: '#FF3B30',
      secondary: '#BE123C',
      bg: '#0F0F12',
      surface: '#191215',
      card: 'rgba(31, 18, 21, 0.88)'
    },
    cyan: {
      id: 'cyan',
      name: 'Aqua Glacier',
      primary: '#64D2FF',
      secondary: '#0284C7',
      bg: '#040B14',
      surface: '#0B1726',
      card: 'rgba(14, 27, 44, 0.88)'
    },
    titanium: {
      id: 'titanium',
      name: 'Titanium',
      primary: '#E5E5EA',
      secondary: '#636366',
      bg: '#0A0A0B',
      surface: '#161618',
      card: 'rgba(28, 28, 31, 0.88)'
    }
  };

  let currentActiveTheme = CURATED_THEMES.blue;

  function hexToRgba(hex, alpha) {
    let c = (hex || '#0A84FF').replace('#', '');
    if (c.length === 3) c = c.split('').map(x => x + x).join('');
    const num = parseInt(c, 16);
    return `rgba(${(num >> 16) & 255}, ${(num >> 8) & 255}, ${num & 255}, ${alpha})`;
  }

  function autoMatchSecondaryColor(hex) {
    let c = (hex || '#0A84FF').replace('#', '');
    if (c.length === 3) c = c.split('').map(x => x + x).join('');
    const num = parseInt(c, 16);
    let r = (num >> 16) & 255;
    let g = (num >> 8) & 255;
    let b = num & 255;

    r /= 255; g /= 255; b /= 255;
    const max = Math.max(r, g, b), min = Math.min(r, g, b);
    let h = 0, s = 0, l = (max + min) / 2;
    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case r: h = (g - b) / d + (g < b ? 6 : 0); break;
        case g: h = (b - r) / d + 2; break;
        case b: h = (r - g) / d + 4; break;
      }
      h /= 6;
    }

    h = (h + 0.05) % 1.0;
    l = Math.max(0.25, Math.min(0.75, l - 0.1));

    function hue2rgb(p, q, t) {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1/6) return p + (q - p) * 6 * t;
      if (t < 1/2) return q;
      if (t < 2/3) return p + (q - p) * (2/3 - t) * 6;
      return p;
    }
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    const p = 2 * l - q;
    const rOut = Math.round(hue2rgb(p, q, h + 1/3) * 255);
    const gOut = Math.round(hue2rgb(p, q, h) * 255);
    const bOut = Math.round(hue2rgb(p, q, h - 1/3) * 255);

    return `#${((1 << 24) + (rOut << 16) + (gOut << 8) + bOut).toString(16).slice(1)}`;
  }

  // HSV & RGB Math for Color Studio
  function hsvToRgb(h, s, v) {
    const c = v * s;
    const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
    const m = v - c;
    let r = 0, g = 0, b = 0;
    if (h >= 0 && h < 60) { r = c; g = x; b = 0; }
    else if (h >= 60 && h < 120) { r = x; g = c; b = 0; }
    else if (h >= 120 && h < 180) { r = 0; g = c; b = x; }
    else if (h >= 180 && h < 240) { r = 0; g = x; b = c; }
    else if (h >= 240 && h < 300) { r = x; g = 0; b = c; }
    else if (h >= 300 && h <= 360) { r = c; g = 0; b = x; }
    return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
  }

  function rgbToHex(r, g, b) {
    return '#' + [r, g, b].map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase();
  }

  function hexToHsv(hex) {
    let c = (hex || '#0A84FF').replace('#', '');
    if (c.length === 3) c = c.split('').map(x => x + x).join('');
    const num = parseInt(c, 16);
    const r = ((num >> 16) & 255) / 255;
    const g = ((num >> 8) & 255) / 255;
    const b = (num & 255) / 255;

    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    const d = max - min;
    let h = 0;
    const s = max === 0 ? 0 : d / max;
    const v = max;

    if (max !== min) {
      switch (max) {
        case r: h = ((g - b) / d + (g < b ? 6 : 0)) * 60; break;
        case g: h = ((b - r) / d + 2) * 60; break;
        case b: h = ((r - g) / d + 4) * 60; break;
      }
    }
    return { h: Math.round(h), s: s, v: v };
  }

  let activeColorTarget = 'primary';
  let currentColorH = 210;
  let currentColorS = 1;
  let currentColorV = 1;
  let initialModalHex = '#0A84FF';
  let isDraggingSquare = false;

  function updateColorPickerUI(updateCrosshair = true) {
    const square = document.getElementById('colorSatValSquare');
    const crosshair = document.getElementById('colorSquareCrosshair');
    const hueSlider = document.getElementById('colorHueSlider');
    const currentSwatch = document.getElementById('pickerCurrentSwatch');
    const hexInput = document.getElementById('colorCustomHexInput');
    const headerDot = document.getElementById('pickerHeaderPreview');

    if (square) square.style.backgroundColor = `hsl(${currentColorH}, 100%, 50%)`;
    if (hueSlider) hueSlider.value = currentColorH;

    const [r, g, b] = hsvToRgb(currentColorH, currentColorS, currentColorV);
    const hex = rgbToHex(r, g, b);

    if (currentSwatch) currentSwatch.style.backgroundColor = hex;
    if (headerDot) {
      headerDot.style.backgroundColor = hex;
      headerDot.style.boxShadow = `0 0 10px ${hex}`;
    }
    if (hexInput && document.activeElement !== hexInput) {
      hexInput.value = hex.replace('#', '');
    }

    if (updateCrosshair && square && crosshair) {
      const x = currentColorS * 100;
      const y = (1 - currentColorV) * 100;
      crosshair.style.left = `${x}%`;
      crosshair.style.top = `${y}%`;
    }

    // Real-time live preview update
    if (currentActiveTheme) {
      const tempPrimary = (activeColorTarget === 'primary') ? hex : currentActiveTheme.primary;
      const tempSecondary = (activeColorTarget === 'secondary') ? hex : currentActiveTheme.secondary;
      const tempBg = (activeColorTarget === 'bg') ? hex : currentActiveTheme.bg;
      const tempSurface = (activeColorTarget === 'surface') ? hex : currentActiveTheme.surface;
      updateThemeLivePreview(tempPrimary, tempSecondary, tempBg, tempSurface);
    }
  }

  function openColorPickerModal(targetKey) {
    activeColorTarget = targetKey;
    const modal = document.getElementById('customColorPickerModal');
    const titleEl = document.getElementById('colorPickerModalTitle');
    const initialSwatch = document.getElementById('pickerInitialSwatch');

    const titles = {
      primary: 'Accent 1 (Gradient Start)',
      secondary: 'Accent 2 (Gradient End)',
      bg: 'Background Color 1 (Start)',
      bgEnd: 'Background Color 2 (End)',
      surface: 'Card & Surface Color'
    };

    if (titleEl) titleEl.textContent = titles[targetKey] || 'Color Studio';

    let curHex = '#0A84FF';
    if (currentActiveTheme) {
      if (targetKey === 'primary') curHex = currentActiveTheme.primary || '#0A84FF';
      else if (targetKey === 'secondary') curHex = currentActiveTheme.secondary || '#0060DF';
      else if (targetKey === 'bg') curHex = currentActiveTheme.bg || '#000000';
      else if (targetKey === 'bgEnd') curHex = currentActiveTheme.bgEnd || currentActiveTheme.bg || '#070710';
      else if (targetKey === 'surface') curHex = currentActiveTheme.surface || '#121214';
    }

    initialModalHex = curHex;
    if (initialSwatch) initialSwatch.style.backgroundColor = curHex;

    const hsv = hexToHsv(curHex);
    currentColorH = hsv.h;
    currentColorS = hsv.s;
    currentColorV = hsv.v;

    if (modal) modal.classList.remove('hidden');
    requestAnimationFrame(() => {
      updateColorPickerUI(true);
    });
  }

  function applyColorPickerSelection() {
    const [r, g, b] = hsvToRgb(currentColorH, currentColorS, currentColorV);
    const hex = rgbToHex(r, g, b);

    if (!currentActiveTheme) currentActiveTheme = { ...CURATED_THEMES.blue };
    if (activeColorTarget === 'primary') {
      currentActiveTheme.primary = hex;
    } else if (activeColorTarget === 'secondary') {
      currentActiveTheme.secondary = hex;
    } else if (activeColorTarget === 'bg') {
      currentActiveTheme.bg = hex;
    } else if (activeColorTarget === 'bgEnd') {
      currentActiveTheme.bgEnd = hex;
    } else if (activeColorTarget === 'surface') {
      currentActiveTheme.surface = hex;
    }

    currentActiveTheme.id = 'custom';
    applyFullTheme(currentActiveTheme, true);

    const modal = document.getElementById('customColorPickerModal');
    if (modal) modal.classList.add('hidden');

    if (window.AndroidBridge && window.AndroidBridge.showToast) {
      window.AndroidBridge.showToast('Theme updated!');
    }
  }

  function cancelColorPickerSelection() {
    if (currentActiveTheme) {
      updateThemeLivePreview(
        currentActiveTheme.primary,
        currentActiveTheme.secondary,
        currentActiveTheme.bg,
        currentActiveTheme.surface
      );
    }
    const modal = document.getElementById('customColorPickerModal');
    if (modal) modal.classList.add('hidden');
  }

  function initColorPickerStudio() {
    const square = document.getElementById('colorSatValSquare');
    const hueSlider = document.getElementById('colorHueSlider');
    const hexInput = document.getElementById('colorCustomHexInput');
    const applyBtn = document.getElementById('applyColorPickerBtn');
    const cancelBtn = document.getElementById('cancelColorPickerBtn');
    const closeBtn = document.getElementById('closeColorPickerModalBtn');

    function handlePointer(e) {
      if (!square) return;
      const rect = square.getBoundingClientRect();
      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      const clientY = e.touches ? e.touches[0].clientY : e.clientY;

      const x = Math.max(0, Math.min(rect.width, clientX - rect.left));
      const y = Math.max(0, Math.min(rect.height, clientY - rect.top));

      currentColorS = Math.max(0, Math.min(1, x / rect.width));
      currentColorV = Math.max(0, Math.min(1, 1 - (y / rect.height)));

      const crosshair = document.getElementById('colorSquareCrosshair');
      if (crosshair) {
        crosshair.style.left = `${currentColorS * 100}%`;
        crosshair.style.top = `${(1 - currentColorV) * 100}%`;
      }
      updateColorPickerUI(false);
    }

    if (square) {
      square.addEventListener('mousedown', (e) => {
        isDraggingSquare = true;
        handlePointer(e);
      });
      window.addEventListener('mousemove', (e) => {
        if (isDraggingSquare) handlePointer(e);
      });
      window.addEventListener('mouseup', () => {
        isDraggingSquare = false;
      });

      square.addEventListener('touchstart', (e) => {
        isDraggingSquare = true;
        handlePointer(e);
      }, { passive: false });
      window.addEventListener('touchmove', (e) => {
        if (isDraggingSquare) {
          e.preventDefault();
          handlePointer(e);
        }
      }, { passive: false });
      window.addEventListener('touchend', () => {
        isDraggingSquare = false;
      });
    }

    if (hueSlider) {
      hueSlider.addEventListener('input', (e) => {
        currentColorH = parseInt(e.target.value, 10);
        updateColorPickerUI(true);
      });
    }

    if (hexInput) {
      hexInput.addEventListener('input', (e) => {
        let val = e.target.value.trim().replace('#', '');
        if (val.length === 6 && /^[0-9A-Fa-f]{6}$/.test(val)) {
          const hsv = hexToHsv('#' + val);
          currentColorH = hsv.h;
          currentColorS = hsv.s;
          currentColorV = hsv.v;
          updateColorPickerUI(true);
        }
      });
    }

    document.querySelectorAll('.swatch-pill').forEach(pill => {
      pill.addEventListener('click', () => {
        const hex = pill.dataset.hex;
        if (hex) {
          const hsv = hexToHsv(hex);
          currentColorH = hsv.h;
          currentColorS = hsv.s;
          currentColorV = hsv.v;
          updateColorPickerUI(true);
        }
      });
    });

    if (applyBtn) applyBtn.addEventListener('click', applyColorPickerSelection);
    if (cancelBtn) cancelBtn.addEventListener('click', cancelColorPickerSelection);
    if (closeBtn) closeBtn.addEventListener('click', cancelColorPickerSelection);

    // Swatch Triggers in Custom Studio
    const triggerMap = [
      { id: 'swatchTriggerPrimary', target: 'primary' },
      { id: 'swatchTriggerSecondary', target: 'secondary' },
      { id: 'swatchTriggerBg', target: 'bg' },
      { id: 'swatchTriggerBgEnd', target: 'bgEnd' },
      { id: 'swatchTriggerSurface', target: 'surface' }
    ];

    triggerMap.forEach(({ id, target }) => {
      const el = document.getElementById(id);
      if (el) {
        el.addEventListener('click', () => openColorPickerModal(target));
      }
    });
  }

  function updateThemeLivePreview(primary, secondary, bg, surface) {
    const previewBtn = document.getElementById('previewCtaBtn');
    if (previewBtn) {
      previewBtn.style.background = `linear-gradient(135deg, ${primary}, ${secondary})`;
      previewBtn.style.boxShadow = `0 6px 20px ${hexToRgba(primary, 0.4)}`;
    }
    const previewBrand = document.getElementById('previewBrandAccent');
    if (previewBrand) {
      previewBrand.style.color = primary;
    }
    const previewDot = document.querySelector('.preview-tab-dot');
    if (previewDot) previewDot.style.background = primary;
    const previewActiveTab = document.querySelector('.preview-tab.active');
    if (previewActiveTab) previewActiveTab.style.color = primary;
  }

  function applyFullTheme(theme, updateInputs = true) {
    if (!theme) return;
    currentActiveTheme = theme;
    const primary = theme.primary || '#0A84FF';
    const secondary = theme.secondary || '#0060DF';
    const bg = theme.bg || '#000000';
    const bgEnd = theme.bgEnd || theme.bg || '#070710';
    const surface = theme.surface || '#121214';
    const card = theme.card || (surface === '#000000' ? '#121214' : surface);

    currentActiveTheme.bgEnd = bgEnd;

    const doc = document.documentElement;

    // 1. Dynamic Primary & Gradient Accents (Retained across both Light and Dark)
    doc.style.setProperty('--accent-primary', primary);
    doc.style.setProperty('--accent-secondary', secondary);
    doc.style.setProperty('--accent-glow', hexToRgba(primary, 0.38));
    doc.style.setProperty('--accent-gradient', `linear-gradient(135deg, ${primary}, ${secondary})`);
    doc.style.setProperty('--card-border-active', hexToRgba(primary, 0.6));

    doc.style.setProperty('--apple-blue', primary);
    doc.style.setProperty('--apple-glow', hexToRgba(primary, 0.38));
    doc.style.setProperty('--brand-accent', primary);
    doc.style.setProperty('--brand-secondary', secondary);

    // 2. Light vs AMOLED Surface & Background Orchestration
    if (isDarkMode) {
      doc.setAttribute('data-theme', 'amoled');
      doc.style.setProperty('--bg-amoled', bg);
      doc.style.setProperty('--surface-amoled', surface);
      doc.style.setProperty('--card-amoled', card);
      doc.style.setProperty('--app-bg', bg);
      doc.style.setProperty('--bg-gradient-start', bg);
      doc.style.setProperty('--bg-gradient-end', bgEnd);
      doc.style.setProperty('--app-surface', surface);
      doc.style.setProperty('--app-card', card);
      doc.style.setProperty('--app-card-border', 'rgba(255, 255, 255, 0.09)');
      doc.style.setProperty('--input-bg', 'rgba(255, 255, 255, 0.07)');
      doc.style.setProperty('--input-border', 'rgba(255, 255, 255, 0.14)');
      doc.style.setProperty('--text-primary', '#FFFFFF');
      doc.style.setProperty('--text-secondary', '#98989D');
      doc.style.setProperty('--text-tertiary', '#636366');
      doc.style.setProperty('--shadow-main', '0 12px 36px rgba(0, 0, 0, 0.6)');
      if (themeIconDark) themeIconDark.classList.remove('hidden');
      if (themeIconLight) themeIconLight.classList.add('hidden');
    } else {
      doc.setAttribute('data-theme', 'light');
      doc.style.setProperty('--app-bg', '#F4F6F9');
      doc.style.setProperty('--bg-gradient-start', '#F4F6F9');
      doc.style.setProperty('--bg-gradient-end', '#EEF2F6');
      doc.style.setProperty('--app-surface', '#FFFFFF');
      doc.style.setProperty('--app-card', '#FFFFFF');
      doc.style.setProperty('--app-card-border', 'rgba(0, 0, 0, 0.08)');
      doc.style.setProperty('--input-bg', '#FFFFFF');
      doc.style.setProperty('--input-border', '#D1D5DB');
      doc.style.setProperty('--text-primary', '#111827');
      doc.style.setProperty('--text-secondary', '#4B5563');
      doc.style.setProperty('--text-tertiary', '#6B7280');
      doc.style.setProperty('--shadow-main', '0 10px 30px rgba(0, 0, 0, 0.05)');
      if (themeIconDark) themeIconDark.classList.add('hidden');
      if (themeIconLight) themeIconLight.classList.remove('hidden');
    }

    try {
      if (window.AndroidBridge && window.AndroidBridge.updateNativeTheme) {
        window.AndroidBridge.updateNativeTheme(primary, surface, bg, isDarkMode);
      }
    } catch (_) {}

    try {
      localStorage.setItem('mediafetch_theme_config', JSON.stringify({
        id: theme.id || 'custom',
        name: theme.name || 'Custom Theme',
        primary: primary,
        secondary: secondary,
        bg: bg,
        bgEnd: bgEnd,
        surface: surface,
        card: card
      }));
    } catch (_) {}

    // Synchronize Swatch Triggers
    const dotPrimary = document.getElementById('dotPrimary');
    const labelPrimary = document.getElementById('labelPrimary');
    const dotSecondary = document.getElementById('dotSecondary');
    const labelSecondary = document.getElementById('labelSecondary');
    const dotBg = document.getElementById('dotBg');
    const labelBg = document.getElementById('labelBg');
    const dotBgEnd = document.getElementById('dotBgEnd');
    const labelBgEnd = document.getElementById('labelBgEnd');
    const dotSurface = document.getElementById('dotSurface');
    const labelSurface = document.getElementById('labelSurface');

    if (dotPrimary) dotPrimary.style.background = primary;
    if (labelPrimary) labelPrimary.textContent = primary.toUpperCase();
    if (dotSecondary) dotSecondary.style.background = secondary;
    if (labelSecondary) labelSecondary.textContent = secondary.toUpperCase();
    if (dotBg) dotBg.style.background = bg;
    if (labelBg) labelBg.textContent = bg.toUpperCase();
    if (dotBgEnd) dotBgEnd.style.background = bgEnd;
    if (labelBgEnd) labelBgEnd.textContent = bgEnd.toUpperCase();
    if (dotSurface) dotSurface.style.background = surface;
    if (labelSurface) labelSurface.textContent = surface.toUpperCase();

    document.querySelectorAll('.theme-preset-card').forEach(cardEl => {
      const isCardActive = (cardEl.dataset.preset === theme.id);
      cardEl.classList.toggle('active', isCardActive);
    });

    updateLogoThemeSync(primary);
    updateThemeLivePreview(primary, secondary, bg, surface);
  }

  function updateLogoThemeSync(accentHex) {
    if (!accentHex || !accentHex.startsWith('#')) return;
    try {
      let hex = accentHex.replace('#', '');
      if (hex.length === 3) hex = hex.split('').map(c => c + c).join('');
      const r = parseInt(hex.substring(0, 2), 16) / 255;
      const g = parseInt(hex.substring(2, 4), 16) / 255;
      const b = parseInt(hex.substring(4, 6), 16) / 255;

      const max = Math.max(r, g, b);
      const min = Math.min(r, g, b);
      let h = 0;
      if (max !== min) {
        const d = max - min;
        switch (max) {
          case r: h = (g - b) / d + (g < b ? 6 : 0); break;
          case g: h = (b - r) / d + 2; break;
          case b: h = (r - g) / d + 4; break;
        }
        h /= 6;
      }
      const targetHue = Math.round(h * 360);
      const baseHue = 210; // Original logo cyan-blue base hue
      const shift = ((targetHue - baseHue) % 360 + 360) % 360;

      const logoEl = document.querySelector('.brand-app-logo');
      if (logoEl) {
        logoEl.style.filter = (shift === 0) ? 'none' : `hue-rotate(${shift}deg) saturate(1.2)`;
      }
    } catch (_) {}
  }

  // Visual Surface & Glassmorphism System (Solid AMOLED / Liquid Glass / Cyber Aurora)
  let currentVisualStyle = 'glass';

  function applyVisualStyle(styleKey) {
    const validStyles = ['solid', 'glass', 'prism'];
    if (!validStyles.includes(styleKey)) styleKey = 'glass';
    currentVisualStyle = styleKey;
    document.documentElement.setAttribute('data-glass-style', styleKey);

    try {
      localStorage.setItem('mediafetch_visual_style', styleKey);
    } catch (_) {}

    document.querySelectorAll('.visual-style-card').forEach(card => {
      const isActive = (card.getAttribute('data-style') === styleKey);
      card.classList.toggle('active', isActive);
    });
  }

  function initVisualStyle() {
    try {
      const saved = localStorage.getItem('mediafetch_visual_style') || 'glass';
      applyVisualStyle(saved);
    } catch (_) {
      applyVisualStyle('glass');
    }
  }

  // Theme Initializer
  function initTheme() {
    initVisualStyle();
    const savedMode = localStorage.getItem('mediafetch_color_mode');
    isDarkMode = (savedMode !== 'light'); // AMOLED Dark mode by default

    try {
      const savedConfigStr = localStorage.getItem('mediafetch_theme_config');
      if (savedConfigStr) {
        const savedConfig = JSON.parse(savedConfigStr);
        applyFullTheme(savedConfig, true);
        return;
      }
      const legacyAccent = localStorage.getItem('mediafetch_accent_color');
      if (legacyAccent) {
        const matchingPreset = Object.values(CURATED_THEMES).find(t => t.primary.toLowerCase() === legacyAccent.toLowerCase());
        if (matchingPreset) {
          applyFullTheme(matchingPreset, true);
        } else {
          applyFullTheme({
            id: 'custom',
            primary: legacyAccent,
            secondary: autoMatchSecondaryColor(legacyAccent),
            bg: '#000000',
            surface: '#121214',
            card: 'rgba(22, 22, 26, 0.85)'
          }, true);
        }
        return;
      }
    } catch (_) {}

    applyFullTheme(CURATED_THEMES.blue, true);
  }

  themeToggleBtn.addEventListener('click', () => {
    isDarkMode = !isDarkMode;
    localStorage.setItem('mediafetch_color_mode', isDarkMode ? 'dark' : 'light');
    applyFullTheme(currentActiveTheme || CURATED_THEMES.blue, false);
    if (window.AndroidBridge && window.AndroidBridge.showToast) {
      window.AndroidBridge.showToast(isDarkMode ? 'Switched to AMOLED Dark mode' : 'Switched to Clean Light mode');
    }
  });

  downloadsDrawerBtn.addEventListener('click', () => downloadsDrawer.classList.add('open'));
  closeDrawerBtn.addEventListener('click', () => downloadsDrawer.classList.remove('open'));
  dismissShareBtn.addEventListener('click', () => incomingShareBanner.classList.add('hidden'));

  urlInput.addEventListener('input', () => {
    clearUrlBtn.classList.toggle('hidden', !urlInput.value);
  });

  clearUrlBtn.addEventListener('click', () => {
    urlInput.value = '';
    clearUrlBtn.classList.add('hidden');
    urlInput.focus();
  });

  pasteUrlBtn.addEventListener('click', async () => {
    try {
      if (navigator.clipboard && navigator.clipboard.readText) {
        const txt = await navigator.clipboard.readText();
        if (txt) {
          urlInput.value = txt;
          clearUrlBtn.classList.remove('hidden');
          triggerAnalysis(txt);
        }
      }
    } catch (e) {
      if (window.AndroidBridge) window.AndroidBridge.showToast('Please type or paste link');
    }
  });

  fetchBtn.addEventListener('click', () => {
    const val = urlInput.value.trim();
    if (!val) {
      if (window.AndroidBridge) window.AndroidBridge.showToast('Please enter or paste a media link');
      return;
    }
    triggerAnalysis(val);
  });

  function triggerAnalysis(url) {
    loadingCard.classList.remove('hidden');
    mediaWorkspace.classList.add('hidden');

    if (window.AndroidBridge && window.AndroidBridge.fetchMediaInfo) {
      window.AndroidBridge.fetchMediaInfo(url);
    }
  }

  // Incoming Share Action from other apps (Feature A)
  window.onSharedUrlReceived = function (url, platform, isShareSheet) {
    urlInput.value = url;
    clearUrlBtn.classList.remove('hidden');
    shareBannerText.textContent = `Shared link received from ${platform}`;
    incomingShareBanner.classList.remove('hidden');
    triggerAnalysis(url);
  };

  // Media Info Loaded from native resolver
  window.onMediaInfoLoaded = function (mediaItem) {
    loadingCard.classList.add('hidden');
    mediaWorkspace.classList.remove('hidden');
    currentMedia = mediaItem;

    mediaThumbnail.src = mediaItem.thumbnail || '';
    const isGif = (mediaItem.platform === 'Giphy' || mediaItem.platform === 'Tenor') ||
                  (mediaItem.qualities && mediaItem.qualities.some(q => q.ext === 'gif' || q.id === 'img_gif')) ||
                  (mediaItem.url && mediaItem.url.toLowerCase().includes('.gif')) ||
                  (mediaItem.thumbnail && mediaItem.thumbnail.toLowerCase().includes('.gif')) ||
                  (mediaItem.imageUrls && mediaItem.imageUrls.some(u => u.toLowerCase().includes('.gif'))) ||
                  (mediaItem.title && mediaItem.title.toLowerCase().includes('gif'));

    if (isGif) {
      mediaDurationBadge.textContent = 'GIF';
      mediaDurationBadge.style.background = 'rgba(191,90,242,0.85)';
      mediaDurationBadge.style.color = '#fff';
    } else if (mediaItem.mediaType === 'IMAGE') {
      mediaDurationBadge.textContent = 'PHOTO';
      mediaDurationBadge.style.background = '';
      mediaDurationBadge.style.color = '';
    } else {
      mediaDurationBadge.textContent = mediaItem.formattedDuration || (mediaItem.durationSeconds > 0 ? formatSec(mediaItem.durationSeconds) : 'HQ');
      mediaDurationBadge.style.background = '';
      mediaDurationBadge.style.color = '';
    }
    mediaPlatformBadge.textContent = mediaItem.platform || 'Media';
    mediaTitle.textContent = mediaItem.title || 'Shared Media';
    mediaAuthor.textContent = mediaItem.author || 'Creator';

    // Duplicate Download Detection (Idea 4)
    const duplicateBadge = document.getElementById('duplicateDownloadedBadge');
    if (duplicateBadge) {
      let isDownloaded = false;
      const targetUrl = mediaItem.pageUrl || mediaItem.directUrl || (urlInput ? urlInput.value.trim() : '');
      if (window.AndroidBridge && window.AndroidBridge.checkAlreadyDownloaded && targetUrl) {
        try {
          isDownloaded = window.AndroidBridge.checkAlreadyDownloaded(targetUrl);
        } catch (_) {}
      }
      if (!isDownloaded && allTasks && allTasks.length > 0 && targetUrl) {
        const checkUrl = targetUrl.toLowerCase();
        isDownloaded = allTasks.some(t => t.status === 'COMPLETED' && (
          (t.pageUrl && t.pageUrl.toLowerCase() === checkUrl) ||
          (t.downloadUrl && t.downloadUrl.toLowerCase() === checkUrl) ||
          (t.url && t.url.toLowerCase() === checkUrl)
        ));
      }
      if (isDownloaded) {
        duplicateBadge.classList.remove('hidden');
      } else {
        duplicateBadge.classList.add('hidden');
      }
    }

    const hasVideo = (mediaItem.qualities && mediaItem.qualities.some(q => !q.isAudioOnly && !q.isImage)) ||
                     (mediaItem.carouselSlides && mediaItem.carouselSlides.some(s => s.mediaType === 'video'));
    const hasPhotos = mediaItem.mediaType === 'IMAGE' || 
                      isGif ||
                      (mediaItem.qualities && mediaItem.qualities.some(q => q.isImage)) ||
                      (mediaItem.carouselSlides && mediaItem.carouselSlides.some(s => s.mediaType === 'image')) ||
                      (mediaItem.imageUrls && mediaItem.imageUrls.length > 0) ||
                      Boolean(mediaItem.thumbnail && mediaItem.thumbnail.startsWith('http'));
    const isImageOnly = !hasVideo && hasPhotos;
    const isAudioOnly = mediaItem.mediaType === 'AUDIO' || mediaItem.platform === 'Spotify' || (mediaItem.qualities && mediaItem.qualities.length > 0 && mediaItem.qualities.every(q => q.isAudioOnly));

    const videoTabBtn = document.querySelector('.seg-tab[data-tab="video"]');
    const audioTabSegBtn = document.querySelector('.seg-tab[data-tab="audio"]');
    const trimmerTabBtn = document.querySelector('.seg-tab[data-tab="trimmer"]');

    if (hasPhotos) {
      imageTabBtn.classList.remove('hidden');
    } else {
      imageTabBtn.classList.add('hidden');
    }

    if (hasVideo) {
      if (videoTabBtn) videoTabBtn.classList.remove('hidden');
      if (trimmerTabBtn) trimmerTabBtn.classList.remove('hidden');
      if (audioTabSegBtn) audioTabSegBtn.classList.remove('hidden');
    } else if (isAudioOnly) {
      if (videoTabBtn) videoTabBtn.classList.add('hidden');
      if (trimmerTabBtn) trimmerTabBtn.classList.remove('hidden');
      if (audioTabSegBtn) audioTabSegBtn.classList.remove('hidden');
    } else {
      // Photo-only media
      if (videoTabBtn) videoTabBtn.classList.add('hidden');
      if (trimmerTabBtn) trimmerTabBtn.classList.add('hidden');
      if (audioTabSegBtn) audioTabSegBtn.classList.add('hidden');
    }

    renderQualities(mediaItem.qualities || []);

    if (isAudioOnly) {
      switchTab('audio');
    } else if (isImageOnly || isGif) {
      switchTab('images');
    } else {
      switchTab('video');
    }

    // Carousel Multi-Slide Ribbon
    if (mediaItem.carouselSlides && mediaItem.carouselSlides.length > 0) {
      renderCarousel(mediaItem.carouselSlides);
      if (carouselSection) carouselSection.classList.remove('hidden');
    } else if (mediaItem.imageUrls && mediaItem.imageUrls.length > 1) {
      const synthSlides = mediaItem.imageUrls.map((u, i) => ({
        slideIndex: i + 1,
        url: u,
        thumbnail: u,
        mediaType: 'image'
      }));
      renderCarousel(synthSlides);
      if (carouselSection) carouselSection.classList.remove('hidden');
    } else {
      if (carouselSection) carouselSection.classList.add('hidden');
    }

    if (mediaItem.isPlaylist && mediaItem.playlistItems && mediaItem.playlistItems.length > 0) {
      const isSpotifyPlaylist = mediaItem.platform === 'Spotify' || isAudioOnly;
      renderPlaylist(mediaItem.playlistItems, isSpotifyPlaylist);
      playlistSection.classList.remove('hidden');
    } else {
      playlistSection.classList.add('hidden');
    }

    // Initialize Trimmer parameters
    const totalSec = mediaItem.durationSeconds || 45;
    trimStartSec = 0;
    trimEndSec = Math.min(totalSec, 30);
    if (isAudioOnly) {
      trimMediaType = 'audio';
      if (trimTypeAudio) trimTypeAudio.classList.add('active');
      if (trimTypeVideo) trimTypeVideo.classList.remove('active');
      selectedTrimQualityId = 'audio_mp3_320';
    } else {
      trimMediaType = 'video';
      if (trimTypeVideo) trimTypeVideo.classList.add('active');
      if (trimTypeAudio) trimTypeAudio.classList.remove('active');
      selectedTrimQualityId = '1080p';
    }
    updateTrimmerUI();

    // Subtitles / Captions (CC)
    if (mediaItem.subtitles && mediaItem.subtitles.length > 0) {
      if (subtitleSelectorCard) subtitleSelectorCard.classList.remove('hidden');
      if (subtitleTrackSelect) {
        subtitleTrackSelect.innerHTML = '<option value="">None (No Subtitles)</option>';
        mediaItem.subtitles.forEach(sub => {
          const opt = document.createElement('option');
          opt.value = sub.url || '';
          opt.setAttribute('data-lang', sub.language || 'en');
          opt.textContent = `${sub.label || sub.language || 'Subtitles'}${sub.isAutoGenerated ? ' (Auto)' : ''}`;
          subtitleTrackSelect.appendChild(opt);
        });
      }
    } else {
      if (subtitleSelectorCard) subtitleSelectorCard.classList.add('hidden');
      if (subtitleTrackSelect) subtitleTrackSelect.innerHTML = '<option value="">None (No Subtitles)</option>';
    }

    // Chapters Support
    if (mediaItem.chapters && mediaItem.chapters.length > 0) {
      if (chapterTabBtn) chapterTabBtn.classList.remove('hidden');
      if (chaptersCountBadge) chaptersCountBadge.textContent = `${mediaItem.chapters.length} chapters`;
      renderChapters(mediaItem.chapters);
    } else {
      if (chapterTabBtn) {
        chapterTabBtn.classList.add('hidden');
        if (activeTab === 'chapters') switchTab('video');
      }
      if (chaptersList) chaptersList.innerHTML = '';
    }

    // Populate Rename input (sanitize if raw URL was given as title)
    const validTitle = (mediaItem.title && !mediaItem.title.startsWith('http')) 
      ? mediaItem.title 
      : ((mediaItem.platform || 'Media') + ' Download');
    if (renameInput) renameInput.value = validTitle;
    if (trimRenameInput) trimRenameInput.value = '';
  };

  if (resetRenameBtn) {
    resetRenameBtn.addEventListener('click', () => {
      if (renameInput && currentMedia) {
        const resetTitle = (currentMedia.title && !currentMedia.title.startsWith('http')) 
          ? currentMedia.title 
          : ((currentMedia.platform || 'Media') + ' Download');
        renameInput.value = resetTitle;
      }
    });
  }

  window.onMediaInfoError = function (errMsg) {
    loadingCard.classList.add('hidden');
    if (window.AndroidBridge) window.AndroidBridge.showToast(errMsg);
  };

  function switchTab(tabId) {
    activeTab = tabId;
    segmentBtns.forEach(b => {
      const isActive = b.dataset.tab === tabId;
      b.classList.toggle('active', isActive);
      if (isActive) {
        try {
          b.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
        } catch (_) {}
      }
    });

    tabVideo.classList.toggle('hidden', activeTab !== 'video');
    tabAudio.classList.toggle('hidden', activeTab !== 'audio');
    tabTrimmer.classList.toggle('hidden', activeTab !== 'trimmer');
    tabImages.classList.toggle('hidden', activeTab !== 'images');
    if (tabChapters) tabChapters.classList.toggle('hidden', activeTab !== 'chapters');
  }

  segmentBtns.forEach(btn => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });

  // Render Video, Audio, and Image Quality Cards
  function renderQualities(qualities) {
    videoQualitiesList.innerHTML = '';
    audioQualitiesList.innerHTML = '';
    imageQualitiesList.innerHTML = '';

    const videoList = (qualities || []).filter(q => {
      if (q.isAudioOnly || q.isImage) return false;
      const idLower = (q.id || '').toLowerCase();
      const resLower = (q.resolution || '').toLowerCase();
      const labelLower = (q.label || '').toLowerCase();
      return !idLower.includes('8k') && !idLower.includes('4320') && !resLower.includes('4320') && !labelLower.includes('8k');
    });
    const audioList = (qualities || []).filter(q => q.isAudioOnly);
    let imageList = (qualities || []).filter(q => q.isImage);

    const isGifMedia = (currentMedia && (
      currentMedia.platform === 'Giphy' ||
      currentMedia.platform === 'Tenor' ||
      (currentMedia.url && currentMedia.url.toLowerCase().includes('.gif')) ||
      (currentMedia.thumbnail && currentMedia.thumbnail.toLowerCase().includes('.gif')) ||
      (currentMedia.imageUrls && currentMedia.imageUrls.some(u => u.toLowerCase().includes('.gif'))) ||
      (currentMedia.title && currentMedia.title.toLowerCase().includes('gif'))
    )) || (qualities || []).some(q => q.ext === 'gif' || q.id === 'img_gif');

    // If media is a GIF, ensure an animated GIF option exists in imageList
    if (isGifMedia && imageList.length > 0) {
      const hasGif = imageList.some(q => q.ext === 'gif' || q.id === 'img_gif');
      if (!hasGif) {
        const rawImg = (currentMedia.imageUrls && currentMedia.imageUrls.length > 0 && currentMedia.imageUrls[0])
          ? currentMedia.imageUrls[0]
          : (currentMedia.thumbnail && currentMedia.thumbnail.startsWith('http') ? currentMedia.thumbnail : (currentMedia.url || ''));
        imageList.unshift({
          id: 'img_gif',
          label: 'Animated GIF (Original Motion)',
          resolution: 'GIF Animation • Infinite Loop',
          format: 'Animation • GIF',
          ext: 'gif',
          estimatedSizeBytes: 3 * 1024 * 1024,
          formattedSize: '3.0 MB',
          isImage: true,
          directDownloadUrl: rawImg
        });
      }
    }

    // If media is a photo or has images/thumbnails, synthesize image options if missing
    if (imageList.length === 0 && currentMedia) {
      let rawImg = (currentMedia.imageUrls && currentMedia.imageUrls.length > 0 && currentMedia.imageUrls[0])
        ? currentMedia.imageUrls[0]
        : (currentMedia.thumbnail && currentMedia.thumbnail.startsWith('http') ? currentMedia.thumbnail : '');

      if (!rawImg && currentMedia.url) {
        const ytM = currentMedia.url.match(/(?:v=|shorts\/|youtu\.be\/|embed\/)([a-zA-Z0-9_-]{11})/);
        if (ytM && ytM[1]) {
          rawImg = `https://i.ytimg.com/vi/${ytM[1]}/maxresdefault.jpg`;
        }
      }

      if (rawImg) {
        const isVid = !isGifMedia && currentMedia.mediaType !== 'IMAGE';
        const synthList = [];
        if (isGifMedia) {
          synthList.push({
            id: 'img_gif',
            label: 'Animated GIF (Original Motion)',
            resolution: 'GIF Animation • Infinite Loop',
            format: 'Animation • GIF',
            ext: 'gif',
            estimatedSizeBytes: 3 * 1024 * 1024,
            formattedSize: '3.0 MB',
            isImage: true,
            directDownloadUrl: rawImg
          });
        }
        synthList.push(
          {
            id: 'img_orig',
            label: isVid ? 'Original Thumbnail (Ultra HD)' : (isGifMedia ? 'Original GIF (Ultra HD)' : 'Original Photo (Ultra HD)'),
            resolution: isVid ? 'Max Resolution Cover • 100% Quality' : (isGifMedia ? 'Original Resolution • Animated GIF' : 'Original Resolution • 100% Quality'),
            format: isGifMedia ? 'Animation • GIF' : 'Image • JPG',
            ext: isGifMedia ? 'gif' : 'jpg',
            estimatedSizeBytes: 4 * 1024 * 1024,
            formattedSize: '4.0 MB',
            isImage: true,
            directDownloadUrl: rawImg
          },
          {
            id: 'img_1080p',
            label: isVid ? 'Full HD Thumbnail (1080p)' : 'Full HD Photo (1080p)',
            resolution: '1920×1080 • Web Crisp',
            format: 'Image • JPG',
            ext: 'jpg',
            estimatedSizeBytes: Math.round(1.5 * 1024 * 1024),
            formattedSize: '1.5 MB',
            isImage: true,
            directDownloadUrl: rawImg
          },
          {
            id: 'img_webp',
            label: 'WebP High Efficiency',
            resolution: 'Modern Compact Format',
            format: 'Image • WebP Lossy',
            ext: 'webp',
            estimatedSizeBytes: 800 * 1024,
            formattedSize: '800 KB',
            isImage: true,
            directDownloadUrl: rawImg
          },
          {
            id: 'img_png',
            label: 'PNG Lossless',
            resolution: 'Sharp Details & Alpha',
            format: 'Image • PNG Uncompressed',
            ext: 'png',
            estimatedSizeBytes: Math.round(5.2 * 1024 * 1024),
            formattedSize: '5.2 MB',
            isImage: true,
            directDownloadUrl: rawImg
          }
        );
        imageList = synthList;
      }
    }

    function formatSizeDisplay(q) {
      if (q && q.formattedSize && q.formattedSize !== 'undefined' && q.formattedSize.trim() !== '') {
        return q.formattedSize;
      }
      const bytes = (q && q.estimatedSizeBytes) ? q.estimatedSizeBytes : 0;
      if (bytes > 0) {
        const mb = bytes / (1024 * 1024);
        if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
        if (mb >= 0.1) return mb.toFixed(1) + ' MB';
        return (bytes / 1024).toFixed(0) + ' KB';
      }
      return 'Fast DL';
    }

    // Determine Recommended Video Quality based on user preferences and 8K exclusion rules
    const userVideoPref = (function() {
      try { return localStorage.getItem('mediafetch_default_video_res') || 'auto'; } catch (_) { return 'auto'; }
    })();

    let recommendedVideoId = null;
    if (userVideoPref !== 'auto') {
      const matched = videoList.find(q => {
        const id = (q.id || '').toLowerCase();
        const res = (q.resolution || '').toLowerCase();
        if (userVideoPref === '2160p') return id === '4k' || id === '2160p' || res.includes('2160');
        if (userVideoPref === '1440p') return id === '2k' || id === '1440p' || res.includes('1440');
        if (userVideoPref === '1080p') return id === '1080p' || res.includes('1080');
        if (userVideoPref === '720p') return id === '720p' || res.includes('720');
        if (userVideoPref === '480p') return id === '480p' || res.includes('480');
        return false;
      });
      if (matched) recommendedVideoId = matched.id;
    }

    // Smart fallback if user preference not found or set to auto (prefer 1080p Full HD -> 720p -> 4K)
    if (!recommendedVideoId && videoList.length > 0) {
      const bestAvailable = videoList.find(q => {
        const id = (q.id || '').toLowerCase();
        return id === '1080p' || id.includes('1080');
      }) || videoList.find(q => {
        const id = (q.id || '').toLowerCase();
        return id === '720p' || id.includes('720');
      }) || videoList.find(q => {
        const id = (q.id || '').toLowerCase();
        return id === '4k' || id === '2160p' || id === '1440p';
      });
      recommendedVideoId = bestAvailable ? bestAvailable.id : videoList[0].id;
    }

    // Videos
    if (videoList.length === 0) {
      const emptyMsg = document.createElement('div');
      emptyMsg.className = 'empty-category-msg';
      emptyMsg.textContent = (currentMedia && (currentMedia.mediaType === 'IMAGE' || isGifMedia)) 
        ? 'This item is an Image / GIF. Switch to the "Photos & Images" tab to download.'
        : 'No video streams available for this item.';
      videoQualitiesList.appendChild(emptyMsg);
    } else {
      videoList.forEach(q => {
        const card = document.createElement('div');
        card.className = 'quality-card-row';
        const idLower = (q.id || '').toLowerCase();
        const resLower = (q.resolution || '').toLowerCase();
        const is4K = idLower === '4k' || idLower === '2160p' || resLower.includes('2160');
        const is2K = idLower === '2k' || idLower === '1440p' || resLower.includes('1440');
        const is1080p = idLower === '1080p' || resLower.includes('1080');
        const is720p = idLower === '720p' || resLower.includes('720');
        const isRecommended = (q.id === recommendedVideoId);

        let chipHtml = '';
        let speedNotice = 'Fast & Balanced';
        if (isRecommended) {
          chipHtml = is4K
            ? '<span class="chip-tag">RECOMMENDED • 4K</span>'
            : (is1080p ? '<span class="chip-tag">RECOMMENDED • FAST</span>' : '<span class="chip-tag">RECOMMENDED</span>');
        } else if (is4K) {
          chipHtml = '<span class="chip-tag" style="background:rgba(255,149,0,0.18);color:var(--apple-orange,#ff9500);">4K STUDIO • SLOWER DL</span>';
        } else if (is2K) {
          chipHtml = '<span class="chip-tag" style="background:rgba(191,90,242,0.18);color:var(--apple-purple,#bf5af2);">2K QUAD HD • MODERATE</span>';
        } else if (is1080p) {
          chipHtml = '<span class="chip-tag" style="background:rgba(48,209,88,0.18);color:var(--apple-green,#30d158);">FULL HD • FAST</span>';
        } else if (is720p) {
          chipHtml = '<span class="chip-tag" style="background:rgba(10,132,255,0.18);color:var(--apple-blue,#0a84ff);">HD • HIGH SPEED</span>';
        }

        if (is4K) {
          speedNotice = 'Slower Speed (Large File)';
        } else if (is2K) {
          speedNotice = 'Moderate Speed';
        } else if (is1080p) {
          speedNotice = 'Fast & Balanced';
        } else if (is720p) {
          speedNotice = 'High Speed (Light File)';
        } else {
          speedNotice = 'Instant Download (Small File)';
        }

        card.innerHTML = `
          <div class="card-row-left">
            <div class="res-glyph-box">${q.id.toUpperCase()}</div>
            <div class="card-details-text">
              <div class="card-title-line">
                <span class="card-label-name">${q.label}</span>
                ${chipHtml}
              </div>
              <span class="card-specs-sub">${q.resolution} • ${q.format} • ${speedNotice}</span>
            </div>
          </div>
          <div class="card-row-right">
            <span class="size-pill-badge">${formatSizeDisplay(q)}</span>
            <div class="circle-dl-glyph">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
            </div>
          </div>
        `;

        card.addEventListener('click', () => {
          if (is4K && window.AndroidBridge && window.AndroidBridge.showToast) {
            window.AndroidBridge.showToast('Note: 4K Ultra HD files are larger and take a bit longer to download.');
          }
          triggerDownload(q.id, false, 0, 0);
        });
        videoQualitiesList.appendChild(card);
      });
    }

    // Audios
    const userAudioPref = (function() {
      try { return localStorage.getItem('mediafetch_default_audio_fmt') || 'mp3_320'; } catch (_) { return 'mp3_320'; }
    })();

    let recommendedAudioId = null;
    if (audioList.length > 0) {
      const matched = audioList.find(q => {
        const id = (q.id || '').toLowerCase();
        if (userAudioPref === 'mp3_320') return id.includes('320') || id.includes('best');
        if (userAudioPref === 'mp3_192') return id.includes('192');
        if (userAudioPref === 'mp3_128') return id.includes('128');
        if (userAudioPref === 'm4a') return id.includes('m4a') || id.includes('aac');
        if (userAudioPref === 'wav') return id.includes('wav') || id.includes('flac');
        return false;
      });
      recommendedAudioId = matched ? matched.id : audioList[0].id;
    }

    if (audioList.length === 0) {
      const emptyMsg = document.createElement('div');
      emptyMsg.className = 'empty-category-msg';
      emptyMsg.textContent = (currentMedia && (currentMedia.mediaType === 'IMAGE' || isGifMedia))
        ? 'No audio tracks for static or animated image media.'
        : 'No audio streams available for this item.';
      audioQualitiesList.appendChild(emptyMsg);
    } else {
      audioList.forEach(q => {
        const card = document.createElement('div');
        card.className = 'quality-card-row';
        const isRecommended = (q.id === recommendedAudioId);

        card.innerHTML = `
          <div class="card-row-left">
            <div class="res-glyph-box audio-glyph">HQ</div>
            <div class="card-details-text">
              <div class="card-title-line">
                <span class="card-label-name">${q.label}</span>
                ${isRecommended ? '<span class="chip-tag" style="background:rgba(255,159,10,0.18);color:var(--apple-orange);">RECOMMENDED</span>' : ''}
              </div>
              <span class="card-specs-sub">${q.resolution} • ${q.format}</span>
            </div>
          </div>
          <div class="card-row-right">
            <span class="size-pill-badge">${formatSizeDisplay(q)}</span>
            <div class="circle-dl-glyph audio-circle">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
            </div>
          </div>
        `;

        card.addEventListener('click', () => triggerDownload(q.id, false, 0, 0));
        audioQualitiesList.appendChild(card);
      });
    }

    // Images & GIFs (Photos from Instagram, Pinterest, Reddit, Giphy, Tenor, Stock)
    const userImgPref = (function() {
      try { return localStorage.getItem('mediafetch_default_img_fmt') || 'original'; } catch (_) { return 'original'; }
    })();

    let recommendedImgId = null;
    if (imageList.length > 0) {
      const matched = imageList.find(q => {
        const id = (q.id || '').toLowerCase();
        if (userImgPref === 'original') return id.includes('orig') || id.includes('gif');
        if (userImgPref === '1080p') return id.includes('1080');
        if (userImgPref === 'webp') return id.includes('webp');
        if (userImgPref === 'png') return id.includes('png');
        return false;
      });
      recommendedImgId = matched ? matched.id : imageList[0].id;
    }

    if (imageList.length === 0) {
      const emptyMsg = document.createElement('div');
      emptyMsg.className = 'empty-category-msg';
      emptyMsg.textContent = 'No photos or images available for this item.';
      imageQualitiesList.appendChild(emptyMsg);
    } else {
      imageList.forEach(q => {
        const card = document.createElement('div');
        card.className = 'quality-card-row';
        const isGifCard = q.ext === 'gif' || q.id === 'img_gif' || (q.format && q.format.toLowerCase().includes('gif'));
        const isRecommended = (q.id === recommendedImgId);

        let chipHtml = '';
        if (isGifCard) {
          chipHtml = '<span class="chip-tag" style="background:rgba(191,90,242,0.18);color:var(--apple-purple);">ANIMATED GIF</span>';
        } else if (isRecommended) {
          chipHtml = '<span class="chip-tag">RECOMMENDED</span>';
        }

        card.innerHTML = `
          <div class="card-row-left">
            <div class="res-glyph-box" style="color:${isGifCard ? 'var(--apple-purple)' : 'var(--apple-cyan)'}">${isGifCard ? 'GIF' : '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:middle;"><rect x="3" y="3" width="18" height="18" rx="3" ry="3"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>'}</div>
            <div class="card-details-text">
              <div class="card-title-line">
                <span class="card-label-name">${q.label}</span>
                ${chipHtml}
              </div>
              <span class="card-specs-sub">${q.resolution} • ${q.format}</span>
            </div>
          </div>
          <div class="card-row-right">
            <span class="size-pill-badge">${formatSizeDisplay(q)}</span>
            <div class="circle-dl-glyph" style="background:${isGifCard ? 'var(--apple-purple)' : 'var(--apple-cyan)'}">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
            </div>
          </div>
        `;

        card.addEventListener('click', () => triggerDownload(q.id, false, 0, 0));
        imageQualitiesList.appendChild(card);
      });
    }
  }

  // Feature B: Zero-Waste Range Trimmer Controller
  function formatSec(sec, forceHours = false) {
    if (!sec || isNaN(sec) || sec < 0) sec = 0;
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = Math.floor(sec % 60);
    if (h > 0 || forceHours) {
      return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    }
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  }

  function parseSec(txt) {
    if (!txt) return 0;
    let clean = txt.toString().trim().toLowerCase();
    if (!clean) return 0;
    // Strip trailing or embedded units like "seconds", "secs", "sec", "s"
    clean = clean.replace(/([0-9]+)\s*(?:seconds|second|secs|sec|s)/g, '$1');
    clean = clean.replace(/[;,]/g, ':').trim();

    // If user enters e.g. "2.30" or "02.30" or "1.45" where dot was used instead of colon:
    if (clean.includes('.') && !clean.includes(':')) {
      const dotParts = clean.split('.');
      if (dotParts.length === 2 && dotParts[1].length <= 2) {
        clean = dotParts[0] + ':' + dotParts[1];
      }
    }

    const parts = clean.split(':').map(p => parseFloat(p.trim()) || 0);
    if (parts.length === 3) {
      // HH:MM:SS
      return Math.round((parts[0] * 3600) + (parts[1] * 60) + parts[2]);
    } else if (parts.length === 2) {
      // MM:SS (e.g. 02:30 -> 150s, 01:45 -> 105s)
      return Math.round((parts[0] * 60) + parts[1]);
    } else if (parts.length === 1) {
      return Math.round(parts[0]);
    }
    return 0;
  }

  function formatBytes(bytes) {
    if (bytes >= 1024 * 1024 * 1024) return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB';
    return bytes + ' B';
  }

  function updateTrimmerUI(skipInputs = false) {
    if (!currentMedia) return;
    const totalSec = currentMedia.durationSeconds || 45;
    const forceH = totalSec >= 3600;

    if (!skipInputs) {
      trimStartInput.value = formatSec(trimStartSec, forceH);
      trimEndInput.value = formatSec(trimEndSec, forceH);
    }

    sliderMinLabel.textContent = forceH ? "00:00:00" : "00:00";
    sliderMaxLabel.textContent = formatSec(totalSec, forceH);

    const leftPercent = (trimStartSec / Math.max(1, totalSec)) * 100;
    const widthPercent = ((trimEndSec - trimStartSec) / Math.max(1, totalSec)) * 100;
    sliderFill.style.left = `${leftPercent}%`;
    sliderFill.style.width = `${Math.max(3, widthPercent)}%`;

    const clipDuration = Math.max(1, trimEndSec - trimStartSec);
    const ratio = clipDuration / Math.max(1, totalSec);

    renderTrimQualities(ratio, clipDuration);
  }

  function renderTrimQualities(ratio, clipDuration) {
    if (!currentMedia || !trimQualitiesGrid) return;
    trimQualitiesGrid.innerHTML = '';

    const list = (currentMedia.qualities || []).filter(q => {
      if (trimMediaType === 'audio') return q.isAudioOnly;
      return !q.isAudioOnly && !q.isImage;
    });

    if (list.length === 0) {
      trimQualitiesGrid.innerHTML = '<div style="grid-column:span 2;font-size:11.5px;color:var(--text-tertiary);text-align:center;padding:8px;">No options available</div>';
      return;
    }

    const found = list.find(q => q.id === selectedTrimQualityId);
    if (!found) {
      const preferred = list.find(q => (q.id || '').toLowerCase() === '1080p') ||
                        list.find(q => (q.id || '').toLowerCase() === '720p') ||
                        list[0];
      selectedTrimQualityId = preferred.id;
    }

    let activeSelectedQ = list.find(q => q.id === selectedTrimQualityId) || list[0];

    list.forEach(q => {
      const isSelected = q.id === selectedTrimQualityId;
      if (isSelected) activeSelectedQ = q;

      const fullBytes = q.estimatedSizeBytes || (1024 * 1024 * 12);
      const trimBytes = Math.max(1024 * 100, Math.round(fullBytes * ratio));
      const sizeText = formatBytes(trimBytes);

      const card = document.createElement('div');
      card.className = `trim-quality-card ${isSelected ? 'selected' : ''}`;
      card.innerHTML = `
        <div class="trim-q-top">
          <span class="trim-q-name">${q.label.split(' ')[0]}</span>
          <span class="trim-q-fmt">${(q.ext || 'MP4').toUpperCase()}</span>
        </div>
        <div class="trim-q-size"><svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.2" class="pill-svg"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg> ~${sizeText}</div>
      `;

      card.addEventListener('click', () => {
        selectedTrimQualityId = q.id;
        updateTrimmerUI();
      });

      trimQualitiesGrid.appendChild(card);
    });

    const activeFullBytes = activeSelectedQ.estimatedSizeBytes || (1024 * 1024 * 12);
    const activeTrimBytes = Math.max(1024 * 100, Math.round(activeFullBytes * ratio));
    const activeSizeText = formatBytes(activeTrimBytes);

    sliderSelectedLabel.textContent = `Selected: ${formatSec(clipDuration)} (~${activeSizeText})`;
    const typeLabel = trimMediaType === 'video' ? 'Clip' : 'Audio';
    const qualityLabel = activeSelectedQ.label.split(' ')[0];
    trimDownloadBtnText.textContent = `Download Trimmed ${typeLabel} • ${qualityLabel} (${activeSizeText})`;
  }

  if (trimTypeVideo) {
    trimTypeVideo.addEventListener('click', () => {
      trimMediaType = 'video';
      trimTypeVideo.classList.add('active');
      trimTypeAudio.classList.remove('active');
      selectedTrimQualityId = '1080p';
      updateTrimmerUI();
    });
  }

  if (trimTypeAudio) {
    trimTypeAudio.addEventListener('click', () => {
      trimMediaType = 'audio';
      trimTypeAudio.classList.add('active');
      trimTypeVideo.classList.remove('active');
      selectedTrimQualityId = 'audio_mp3_320';
      updateTrimmerUI();
    });
  }

  function handleStartInputChange() {
    const val = parseSec(trimStartInput.value);
    trimStartSec = Math.max(0, val);
    if (trimStartSec >= trimEndSec) {
      trimEndSec = trimStartSec + 30;
    }
    updateTrimmerUI(false);
  }

  function handleEndInputChange() {
    const hasKnownDuration = currentMedia && currentMedia.durationSeconds && currentMedia.durationSeconds > 0;
    const max = hasKnownDuration ? currentMedia.durationSeconds : (24 * 3600);
    const val = parseSec(trimEndInput.value);
    trimEndSec = val > 0 ? (hasKnownDuration ? Math.min(max, val) : val) : (trimStartSec + 30);
    if (trimEndSec <= trimStartSec) {
      trimStartSec = Math.max(0, trimEndSec - 10);
    }
    updateTrimmerUI(false);
  }

  trimStartInput.addEventListener('change', handleStartInputChange);
  trimStartInput.addEventListener('blur', handleStartInputChange);

  trimEndInput.addEventListener('change', handleEndInputChange);
  trimEndInput.addEventListener('blur', handleEndInputChange);

  document.querySelectorAll('.quick-adjust-pill[data-seconds]').forEach(pill => {
    pill.addEventListener('click', () => {
      const add = parseInt(pill.dataset.seconds, 10);
      const hasKnownDuration = currentMedia && currentMedia.durationSeconds && currentMedia.durationSeconds > 0;
      const max = hasKnownDuration ? currentMedia.durationSeconds : (24 * 3600);
      const target = trimEndSec + add;
      trimEndSec = hasKnownDuration ? Math.min(max, target) : target;
      updateTrimmerUI();
    });
  });

  resetTrimBtn.addEventListener('click', () => {
    trimStartSec = 0;
    const hasKnownDuration = currentMedia && currentMedia.durationSeconds && currentMedia.durationSeconds > 0;
    trimEndSec = hasKnownDuration ? currentMedia.durationSeconds : 600;
    updateTrimmerUI();
  });

  downloadTrimmedBtn.addEventListener('click', () => {
    if (trimStartInput && trimEndInput) {
      const sVal = parseSec(trimStartInput.value);
      const eVal = parseSec(trimEndInput.value);
      if (sVal >= 0 && eVal > sVal) {
        trimStartSec = sVal;
        trimEndSec = eVal;
      }
    }
    triggerDownload(selectedTrimQualityId, true, trimStartSec, trimEndSec);
  });

  // Playlist Handler
  function renderPlaylist(items, isSpotify) {
    playlistItemsList.innerHTML = '';
    playlistCountBadge.textContent = `${items.length} items`;
    downloadBatchBtnText.textContent = `Download Selected (${items.length} items)`;

    // Rebuild quality pills depending on platform
    const pillsContainer = document.querySelector('.playlist-quality-pills');
    if (pillsContainer) {
      if (isSpotify) {
        // Audio-only pills for Spotify (no video options)
        pillsContainer.innerHTML = `
          <button type="button" class="pl-quality-pill active" data-quality="audio_mp3_320"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg> 320k Studio MP3</button>
          <button type="button" class="pl-quality-pill" data-quality="audio_mp3_192"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg> 192k Standard MP3</button>
          <button type="button" class="pl-quality-pill" data-quality="audio_mp3_128"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg> 128k Data Saver</button>
          <button type="button" class="pl-quality-pill" data-quality="audio_wav_lossless"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg> Lossless WAV</button>
        `;
        selectedBatchQuality = 'audio_mp3_320';
      } else {
        // Video + Audio pills for YouTube & Google Drive
        pillsContainer.innerHTML = `
          <button type="button" class="pl-quality-pill active" data-quality="1080p"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"></rect><line x1="7" y1="2" x2="7" y2="22"></line><line x1="17" y1="2" x2="17" y2="22"></line><line x1="2" y1="12" x2="22" y2="12"></line></svg> 1080p Full HD</button>
          <button type="button" class="pl-quality-pill" data-quality="720p"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"></rect><line x1="7" y1="2" x2="7" y2="22"></line><line x1="17" y1="2" x2="17" y2="22"></line><line x1="2" y1="12" x2="22" y2="12"></line></svg> 720p HD</button>
          <button type="button" class="pl-quality-pill" data-quality="480p"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"></rect><line x1="7" y1="2" x2="7" y2="22"></line><line x1="17" y1="2" x2="17" y2="22"></line><line x1="2" y1="12" x2="22" y2="12"></line></svg> 480p SD</button>
          <button type="button" class="pl-quality-pill" data-quality="audio_mp3_320"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg> 320k Studio MP3</button>
          <button type="button" class="pl-quality-pill" data-quality="audio_mp3_192"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg> 192k Standard MP3</button>
        `;
        selectedBatchQuality = '1080p';
      }
      // Re-attach click listeners for the freshly created pills
      pillsContainer.querySelectorAll('.pl-quality-pill').forEach((pill) => {
        pill.addEventListener('click', () => {
          pillsContainer.querySelectorAll('.pl-quality-pill').forEach((p) => p.classList.remove('active'));
          pill.classList.add('active');
          selectedBatchQuality = pill.dataset.quality;
        });
      });
    }

    function buildFolderTree(items) {
      const root = { name: 'root', folders: {}, files: [] };
      items.forEach((item, index) => {
        const rawPath = (item.subFolderName || item.subfolder || '').trim();
        if (!rawPath) {
          root.files.push({ item, index });
          return;
        }
        const parts = rawPath.split(/\s*\/\s*/).filter(Boolean);
        let current = root;
        for (const part of parts) {
          if (!current.folders[part]) {
            current.folders[part] = { name: part, folders: {}, files: [], totalCount: 0 };
          }
          current = current.folders[part];
        }
        current.files.push({ item, index });
      });

      function calcCount(node) {
        let count = node.files ? node.files.length : 0;
        for (const sub of Object.values(node.folders)) {
          count += calcCount(sub);
        }
        node.totalCount = count;
        return count;
      }
      calcCount(root);
      return root;
    }

    function updateParentFolderCbs(fromEl) {
      let curr = fromEl.parentElement;
      while (curr && curr.id !== 'playlistItemsList') {
        if (curr.classList.contains('subfolder-items')) {
          const parentGroup = curr.parentElement;
          const parentCb = parentGroup?.querySelector(':scope > .subfolder-header .subfolder-master-cb');
          if (parentCb) {
            const allFiles = curr.querySelectorAll('.playlist-cb');
            const checkedFiles = curr.querySelectorAll('.playlist-cb:checked');
            if (checkedFiles.length === 0) {
              parentCb.checked = false;
              parentCb.indeterminate = false;
            } else if (checkedFiles.length === allFiles.length) {
              parentCb.checked = true;
              parentCb.indeterminate = false;
            } else {
              parentCb.checked = false;
              parentCb.indeterminate = true;
            }
          }
        }
        curr = curr.parentElement;
      }
    }

    function renderTreeLevel(container, node, level = 0) {
      // 1. Subfolders at this level
      for (const [folderName, subNode] of Object.entries(node.folders)) {
        const groupEl = document.createElement('div');
        groupEl.className = 'subfolder-group';

        const headerEl = document.createElement('div');
        headerEl.className = 'subfolder-header';
        headerEl.innerHTML = `
          <div class="subfolder-header-left">
            <span class="subfolder-toggle-icon">▶</span>
            <input type="checkbox" class="subfolder-master-cb" checked title="Select/Deselect folder">
            <span class="subfolder-name"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg> ${folderName}</span>
          </div>
          <span class="subfolder-count">${subNode.totalCount} files</span>
        `;

        const childrenEl = document.createElement('div');
        childrenEl.className = 'subfolder-items';
        childrenEl.style.display = 'none';
        if (level > 0) {
          childrenEl.style.paddingLeft = `${12 + (level * 6)}px`;
        }

        renderTreeLevel(childrenEl, subNode, level + 1);

        let isExpanded = false;
        const toggleIcon = headerEl.querySelector('.subfolder-toggle-icon');
        headerEl.addEventListener('click', (e) => {
          if (e.target.classList.contains('subfolder-master-cb')) return;
          isExpanded = !isExpanded;
          childrenEl.style.display = isExpanded ? 'flex' : 'none';
          toggleIcon.textContent = isExpanded ? '▼' : '▶';
        });

        const masterCb = headerEl.querySelector('.subfolder-master-cb');
        masterCb.addEventListener('change', (e) => {
          const checked = e.target.checked;
          childrenEl.querySelectorAll('.playlist-cb, .subfolder-master-cb').forEach(cb => {
            cb.checked = checked;
            cb.indeterminate = false;
          });
          updateParentFolderCbs(groupEl);
          updatePlaylistSelectedCount();
        });

        groupEl.appendChild(headerEl);
        groupEl.appendChild(childrenEl);
        container.appendChild(groupEl);
      }

      // 2. Direct files at this level (if any)
      if (node.files && node.files.length > 0) {
        node.files.forEach(({ item, index }) => {
          const isImg = item.isImage || /\.(jpg|jpeg|png|webp|gif|bmp)$/i.test(item.title);
          const badgeText = isImg ? 'PHOTO' : (item.formattedDuration && item.formattedDuration !== '00:00' && item.formattedDuration !== 'HD FOOTAGE' ? item.formattedDuration : (item.durationSeconds > 0 ? formatSec(item.durationSeconds) : 'HD MEDIA'));
          const row = document.createElement('div');
          row.className = 'playlist-row';
          row.innerHTML = `
            <input type="checkbox" class="playlist-cb" data-index="${index}" checked>
            <img src="${item.thumbnail}" class="playlist-pic" alt="">
            <div class="playlist-txt">
              <div class="playlist-name">${item.title}</div>
              <div class="playlist-len">${badgeText}</div>
            </div>
          `;

          row.querySelector('.playlist-cb').addEventListener('change', () => {
            updateParentFolderCbs(row);
            updatePlaylistSelectedCount();
          });

          container.appendChild(row);
        });
      }
    }

    const hasSubfolders = items.some(it => (it.subFolderName && it.subFolderName.trim().length > 0) || (it.subfolder && it.subfolder.trim().length > 0));

    if (hasSubfolders) {
      const tree = buildFolderTree(items);
      renderTreeLevel(playlistItemsList, tree, 0);
    } else {
      items.forEach((item, index) => {
        const isImg = item.isImage || /\.(jpg|jpeg|png|webp|gif|bmp)$/i.test(item.title);
        const badgeText = isImg ? 'PHOTO' : (item.formattedDuration && item.formattedDuration !== '00:00' && item.formattedDuration !== 'HD FOOTAGE' ? item.formattedDuration : (item.durationSeconds > 0 ? formatSec(item.durationSeconds) : 'HD MEDIA'));
        const row = document.createElement('div');
        row.className = 'playlist-row';
        row.innerHTML = `
          <input type="checkbox" class="playlist-cb" data-index="${index}" checked>
          <img src="${item.thumbnail}" class="playlist-pic" alt="">
          <div class="playlist-txt">
            <div class="playlist-name">${item.title}</div>
            <div class="playlist-len">${badgeText}</div>
          </div>
        `;

        row.querySelector('.playlist-cb').addEventListener('change', updatePlaylistSelectedCount);
        playlistItemsList.appendChild(row);
      });
    }
  }

  function updatePlaylistSelectedCount() {
    const checked = playlistItemsList.querySelectorAll('.playlist-cb:checked');
    downloadBatchBtnText.textContent = `Download Selected (${checked.length} items)`;
  }

  selectAllBtn.addEventListener('click', () => {
    playlistItemsList.querySelectorAll('.playlist-cb').forEach(cb => cb.checked = true);
    playlistItemsList.querySelectorAll('.subfolder-master-cb').forEach(cb => { cb.checked = true; cb.indeterminate = false; });
    updatePlaylistSelectedCount();
  });

  deselectAllBtn.addEventListener('click', () => {
    playlistItemsList.querySelectorAll('.playlist-cb').forEach(cb => cb.checked = false);
    playlistItemsList.querySelectorAll('.subfolder-master-cb').forEach(cb => { cb.checked = false; cb.indeterminate = false; });
    updatePlaylistSelectedCount();
  });

  // Custom Folder Persistence & Picker
  function updateCustomFolderLabels(folderPath) {
    const display = folderPath ? (folderPath.split('/').pop() || folderPath) : 'Default (Downloads/MediaFetch)';
    if (playlistSavePath) playlistSavePath.textContent = display;
    if (carouselSavePath) carouselSavePath.textContent = display;
  }

  if (window.AndroidBridge && window.AndroidBridge.getCustomFolder) {
    try {
      const savedFolder = window.AndroidBridge.getCustomFolder();
      if (savedFolder) {
        currentCustomFolder = savedFolder;
        updateCustomFolderLabels(savedFolder);
      }
    } catch (_) {}
  }

  window.onCustomFolderSelected = function (folderPath) {
    currentCustomFolder = folderPath;
    updateCustomFolderLabels(folderPath);
    if (window.AndroidBridge && window.AndroidBridge.showToast) {
      window.AndroidBridge.showToast(`Destination saved: ${folderPath}`);
    }
  };

  if (playlistChangeFolderBtn) {
    playlistChangeFolderBtn.addEventListener('click', () => {
      if (window.AndroidBridge && window.AndroidBridge.pickCustomFolder) {
        window.AndroidBridge.pickCustomFolder();
      }
    });
  }

  if (carouselChangeFolderBtn) {
    carouselChangeFolderBtn.addEventListener('click', () => {
      if (window.AndroidBridge && window.AndroidBridge.pickCustomFolder) {
        window.AndroidBridge.pickCustomFolder();
      }
    });
  }

  downloadBatchBtn.addEventListener('click', () => {
    const selected = [];
    playlistItemsList.querySelectorAll('.playlist-cb:checked').forEach(cb => {
      selected.push(parseInt(cb.dataset.index, 10));
    });

    if (selected.length === 0) {
      if (window.AndroidBridge) window.AndroidBridge.showToast('Please select at least one item');
      return;
    }

    const asZip = Boolean(playlistZipToggle && playlistZipToggle.checked);

    if (window.AndroidBridge && window.AndroidBridge.startBatchDownloadWithOptions) {
      window.AndroidBridge.startBatchDownloadWithOptions(
        selectedBatchQuality,
        JSON.stringify(selected),
        asZip,
        currentCustomFolder
      );
    } else if (window.AndroidBridge && window.AndroidBridge.startBatchDownload) {
      window.AndroidBridge.startBatchDownload(selectedBatchQuality, JSON.stringify(selected));
    }
    downloadsDrawer.classList.add('open');
  });


  // Carousel Multi-Slide Studio Rendering & Downloads
  function renderCarousel(slides) {
    if (!carouselSlidesTrack) return;
    carouselSlidesData = slides || [];
    selectedSlideIndices = new Set(carouselSlidesData.map((_, i) => i)); // All selected by default
    currentCarouselFilter = 'all';

    if (carouselCountBadge) carouselCountBadge.textContent = `${carouselSlidesData.length} slides`;
    updateCarouselFilterPillsUI();
    renderFilteredCarouselSlides();
    updateCarouselSelectedCount();
  }

  function renderFilteredCarouselSlides() {
    if (!carouselSlidesTrack) return;
    carouselSlidesTrack.innerHTML = '';

    carouselSlidesData.forEach((slide, idx) => {
      const isVid = slide.mediaType === 'video';
      if (currentCarouselFilter === 'image' && isVid) return;
      if (currentCarouselFilter === 'video' && !isVid) return;

      const isChecked = selectedSlideIndices.has(idx);
      const card = document.createElement('div');
      card.className = `carousel-slide-item ${isChecked ? 'selected' : ''}`;
      card.dataset.index = idx;

      card.innerHTML = `
        <input type="checkbox" class="carousel-slide-checkbox" data-index="${idx}" ${isChecked ? 'checked' : ''}>
        <div class="slide-img-box">
          <img src="${slide.thumbnail || slide.url}" alt="Slide ${slide.slideIndex}" onerror="this.src='data:image/svg+xml;utf8,<svg xmlns=\\'http://www.w3.org/2000/svg\\' width=\\'95\\' height=\\'100\\' fill=\\'%23333\\'><rect width=\\'100%\\' height=\\'100%\\'/></svg>'">
          <span class="slide-badge">#${slide.slideIndex} ${isVid ? 'VIDEO' : 'PHOTO'}</span>
        </div>
        <button type="button" class="slide-action-btn">⬇ Download</button>
      `;

      // Checkbox change listener
      const cb = card.querySelector('.carousel-slide-checkbox');
      cb.addEventListener('change', (e) => {
        e.stopPropagation();
        if (cb.checked) {
          selectedSlideIndices.add(idx);
          card.classList.add('selected');
        } else {
          selectedSlideIndices.delete(idx);
          card.classList.remove('selected');
        }
        updateCarouselSelectedCount();
      });

      // Card tap selects/deselects
      card.addEventListener('click', (e) => {
        if (e.target.closest('.slide-action-btn') || e.target.closest('.carousel-slide-checkbox')) return;
        cb.checked = !cb.checked;
        cb.dispatchEvent(new Event('change'));
      });

      card.querySelector('.slide-action-btn').addEventListener('click', (e) => {
        e.stopPropagation();
        downloadSingleSlide(slide);
      });

      carouselSlidesTrack.appendChild(card);
    });
  }

  function updateCarouselSelectedCount() {
    const count = selectedSlideIndices.size;
    if (carouselSelectedCountBadge) carouselSelectedCountBadge.textContent = `${count} selected`;
    if (downloadSelectedSlidesBtnText) {
      const isZip = Boolean(carouselZipToggle && carouselZipToggle.checked);
      downloadSelectedSlidesBtnText.textContent = isZip
        ? `Download ${count} Slides as .ZIP`
        : `Download Selected (${count} Slides)`;
    }
  }

  function updateCarouselFilterPillsUI() {
    document.querySelectorAll('.carousel-filter-chip').forEach(chip => {
      if (chip.dataset.filter === currentCarouselFilter) {
        chip.classList.add('active');
      } else {
        chip.classList.remove('active');
      }
    });
  }

  document.querySelectorAll('.carousel-filter-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      currentCarouselFilter = chip.dataset.filter || 'all';
      updateCarouselFilterPillsUI();
      renderFilteredCarouselSlides();
    });
  });

  if (carouselSelectAllBtn) {
    carouselSelectAllBtn.addEventListener('click', () => {
      carouselSlidesData.forEach((_, i) => selectedSlideIndices.add(i));
      renderFilteredCarouselSlides();
      updateCarouselSelectedCount();
    });
  }

  if (carouselDeselectBtn) {
    carouselDeselectBtn.addEventListener('click', () => {
      selectedSlideIndices.clear();
      renderFilteredCarouselSlides();
      updateCarouselSelectedCount();
    });
  }

  if (carouselZipToggle) {
    carouselZipToggle.addEventListener('change', () => {
      updateCarouselSelectedCount();
    });
  }

  if (downloadSelectedSlidesBtn) {
    downloadSelectedSlidesBtn.addEventListener('click', () => {
      if (selectedSlideIndices.size === 0) {
        if (window.AndroidBridge) window.AndroidBridge.showToast('Please select at least one slide');
        return;
      }

      const asZip = Boolean(carouselZipToggle && carouselZipToggle.checked);
      const selectedSlides = [];
      selectedSlideIndices.forEach(idx => {
        if (carouselSlidesData[idx]) {
          selectedSlides.push(carouselSlidesData[idx]);
        }
      });

      if (window.AndroidBridge && window.AndroidBridge.startCarouselDownloadWithOptions) {
        window.AndroidBridge.startCarouselDownloadWithOptions(
          JSON.stringify(selectedSlides),
          asZip,
          currentCustomFolder
        );
      } else {
        selectedSlides.forEach((slide, i) => {
          setTimeout(() => downloadSingleSlide(slide), i * 350);
        });
      }

      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast(asZip
          ? `Packaging ${selectedSlides.length} slides into .ZIP archive...`
          : `Downloading ${selectedSlides.length} selected slides...`
        );
      }
      if (downloadsDrawer) downloadsDrawer.classList.add('open');
    });
  }

  function downloadSingleSlide(slide) {
    const isVid = slide.mediaType === 'video';
    const slideItem = {
      directUrl: slide.url,
      downloadUrl: slide.url,
      pageUrl: slide.url,
      title: `${(currentMedia && currentMedia.title) ? currentMedia.title : 'Slide'} - Slide ${slide.slideIndex}`,
      author: (currentMedia && currentMedia.author) ? currentMedia.author : 'Media Creator',
      thumbnail: slide.thumbnail || slide.url,
      mediaType: isVid ? 'video' : 'image'
    };

    if (window.AndroidBridge && window.AndroidBridge.downloadSearchResultWithFormat) {
      window.AndroidBridge.downloadSearchResultWithFormat(JSON.stringify(slideItem), isVid ? '1080p' : 'image');
      if (downloadsDrawer) downloadsDrawer.classList.add('open');
    }
  }

  if (downloadAllSlidesBtn) {
    downloadAllSlidesBtn.addEventListener('click', () => {
      const slides = (currentMedia && currentMedia.carouselSlides && currentMedia.carouselSlides.length > 0)
        ? currentMedia.carouselSlides
        : (currentMedia && currentMedia.imageUrls && currentMedia.imageUrls.length > 1
          ? currentMedia.imageUrls.map((u, i) => ({ slideIndex: i + 1, url: u, thumbnail: u, mediaType: 'image' }))
          : []);

      if (slides.length === 0) {
        if (window.AndroidBridge) window.AndroidBridge.showToast('No slides found to download');
        return;
      }

      slides.forEach((slide, idx) => {
        setTimeout(() => {
          downloadSingleSlide(slide);
        }, idx * 350);
      });

      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast(`Enqueued all ${slides.length} slides!`);
      }
      if (downloadsDrawer) downloadsDrawer.classList.add('open');
    });
  }

  // Download Trigger
  function triggerDownload(qualityId, isTrimmed, startSec, endSec, chapterTitle = '') {
    const baseCustom = renameInput ? renameInput.value.trim() : '';
    const trimCustom = trimRenameInput ? trimRenameInput.value.trim() : '';
    const customTitle = chapterTitle || (isTrimmed
      ? (trimCustom || baseCustom || (currentMedia ? currentMedia.title : ''))
      : (baseCustom || (currentMedia ? currentMedia.title : '')));

    let subtitleUrl = '';
    let subtitleLang = '';
    if (subtitleTrackSelect && subtitleTrackSelect.selectedIndex > 0) {
      const opt = subtitleTrackSelect.options[subtitleTrackSelect.selectedIndex];
      subtitleUrl = opt.value || '';
      subtitleLang = opt.getAttribute('data-lang') || '';
    }

    if (currentMedia && window.AndroidBridge && window.AndroidBridge.checkFileExists) {
      const q = (currentMedia.qualities || []).find(item => item.id === qualityId);
      const ext = q ? q.ext : 'mp4';
      const exists = window.AndroidBridge.checkFileExists(customTitle, ext);
      if (exists) {
        const proceed = confirm(`File Already Downloaded\n\nYou already have "${customTitle}" saved in your device storage.\n\nDo you want to download again?`);
        if (!proceed) return;
      }
    }
    if (window.AndroidBridge) {
      if (window.AndroidBridge.startDownloadWithExtra) {
        window.AndroidBridge.startDownloadWithExtra(
          qualityId,
          Boolean(isTrimmed),
          Math.max(0, Math.floor(startSec || 0)),
          Math.max(0, Math.floor(endSec || 0)),
          customTitle,
          "{}",
          subtitleUrl,
          subtitleLang,
          chapterTitle || ""
        );
      } else if (window.AndroidBridge.startDownload) {
        window.AndroidBridge.startDownload(qualityId, isTrimmed, startSec, endSec, customTitle, "{}");
      }
    }
    if (downloadsDrawer) downloadsDrawer.classList.add('open');
  }

  // Feature 2: Smart Chapter-Based Splitting
  function renderChapters(chapters) {
    if (!chaptersList) return;
    chaptersList.innerHTML = '';
    if (!chapters || chapters.length === 0) return;

    chapters.forEach((chap, idx) => {
      const card = document.createElement('div');
      card.className = 'chapter-item-card';

      const durSec = Math.max(0, (chap.endSeconds || 0) - (chap.startSeconds || 0));
      const rangeStr = `${formatSec(chap.startSeconds)} - ${formatSec(chap.endSeconds)}`;
      const durStr = formatSec(durSec);

      const metaLeft = document.createElement('div');
      metaLeft.className = 'chapter-meta-left';

      const timePill = document.createElement('span');
      timePill.className = 'chapter-time-pill';
      timePill.textContent = `${rangeStr} (${durStr})`;

      const titleSpan = document.createElement('span');
      titleSpan.className = 'chapter-title-text';
      titleSpan.textContent = chap.title || `Chapter ${idx + 1}`;
      titleSpan.title = chap.title || `Chapter ${idx + 1}`;

      metaLeft.appendChild(timePill);
      metaLeft.appendChild(titleSpan);

      const actionsRight = document.createElement('div');
      actionsRight.className = 'chapter-actions-right';

      const trimBtn = document.createElement('button');
      trimBtn.type = 'button';
      trimBtn.className = 'chapter-action-btn';
      trimBtn.innerHTML = '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><circle cx="6" cy="6" r="3"></circle><circle cx="6" cy="18" r="3"></circle><line x1="20" y1="4" x2="8.12" y2="15.88"></line><line x1="14.47" y1="14.48" x2="20" y2="20"></line><line x1="8.12" y1="8.12" x2="12" y2="12"></line></svg> Trim';
      trimBtn.title = 'Open in Clip Trimmer';
      trimBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        switchTab('trimmer');
        trimStartSec = chap.startSeconds;
        trimEndSec = chap.endSeconds;
        if (trimStartInput) trimStartInput.value = formatSec(chap.startSeconds);
        if (trimEndInput) trimEndInput.value = formatSec(chap.endSeconds);
        if (trimRenameInput) trimRenameInput.value = chap.title || '';
        updateTrimmerUI();
      });

      const dlBtn = document.createElement('button');
      dlBtn.type = 'button';
      dlBtn.className = 'chapter-action-btn';
      dlBtn.innerHTML = '⬇️ Download';
      dlBtn.title = 'Download this chapter as separate track';
      dlBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        const bestQ = (currentMedia && currentMedia.qualities)
          ? (currentMedia.qualities.find(q => !q.isAudioOnly && !q.isImage) || currentMedia.qualities[0])
          : null;
        const qId = bestQ ? bestQ.id : '1080p';
        triggerDownload(qId, true, chap.startSeconds, chap.endSeconds, chap.title);
      });

      actionsRight.appendChild(trimBtn);
      actionsRight.appendChild(dlBtn);

      card.appendChild(metaLeft);
      card.appendChild(actionsRight);

      card.addEventListener('click', () => {
        trimBtn.click();
      });

      chaptersList.appendChild(card);
    });
  }

  if (downloadAllChaptersBtn) {
    downloadAllChaptersBtn.addEventListener('click', () => {
      if (!currentMedia || !currentMedia.chapters || currentMedia.chapters.length === 0) {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('No chapters found to download');
        }
        return;
      }
      const bestQ = (currentMedia.qualities)
        ? (currentMedia.qualities.find(q => !q.isAudioOnly && !q.isImage) || currentMedia.qualities[0])
        : null;
      const qId = bestQ ? bestQ.id : '1080p';

      currentMedia.chapters.forEach((chap, idx) => {
        setTimeout(() => {
          triggerDownload(qId, true, chap.startSeconds, chap.endSeconds, chap.title);
        }, idx * 300);
      });

      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast(`Enqueued all ${currentMedia.chapters.length} chapters as separate tracks!`);
      }
      if (downloadsDrawer) downloadsDrawer.classList.add('open');
    });
  }

  // Active Downloads & Tasks
  window.onDownloadTaskUpdated = function (task) {
    const existingIndex = allTasks.findIndex(t => t.id === task.id);
    if (existingIndex >= 0) {
      allTasks[existingIndex] = task;
    } else {
      allTasks.unshift(task);
    }
    renderTasksList();
  };

  window.onTaskListLoaded = function (taskList) {
    allTasks = taskList || [];
    renderTasksList();
  };

  let currentHistoryFilter = 'all';

  function buildTaskCardElement(task, isDrawer = false) {
    const card = document.createElement('div');
    card.className = 'task-card-item';

    const isDone = task.status === 'COMPLETED';
    const isPaused = task.status === 'PAUSED';
    const isDown = task.status === 'DOWNLOADING';
    const isFailed = task.status === 'FAILED';

    const statusTag = isDone 
      ? '<span class="chip-tag" style="background:rgba(48,209,88,0.18);color:var(--apple-green);">SAVED TO GALLERY</span>'
      : (isFailed ? '<span class="chip-tag" style="background:rgba(255,69,58,0.18);color:var(--apple-red);">FAILED</span>' : `<span>• ${task.status}</span>`);

    card.innerHTML = `
      <div class="task-top">
        <img src="${task.thumbnail || 'https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=100'}" class="task-pic" alt="">
        <div class="task-info-grp">
          <div class="task-title-line">${task.title}</div>
          <div class="task-fmt-sub">
            <span>${task.quality ? task.quality.label : 'Media'}</span>
            ${task.isTrimmed ? '<span class="chip-tag">TRIMMED</span>' : ''}
            ${statusTag}
          </div>
        </div>
        <button class="task-delete-btn" data-id="${task.id}" title="Remove from history">&times;</button>
      </div>

      <div class="task-rail">
        <div class="task-bar-fill" style="width: ${task.progressPercent}%;"></div>
      </div>

      <div class="task-bottom-row">
        <div class="task-live-stats">
          ${isDone ? 'Saved in Downloads/MediaFetch' : `${task.progressPercent}% • ${task.formattedSpeed} • ${task.formattedDownloaded}`}
        </div>
        <div class="task-controls">
          ${isDown ? `<button class="ctrl-btn pause-btn" data-id="${task.id}">Pause</button>` : ''}
          ${isPaused ? `<button class="ctrl-btn resume-btn" data-id="${task.id}">Resume</button>` : ''}
          ${!isDone ? `<button class="ctrl-btn cancel-btn" data-id="${task.id}">Cancel</button>` : ''}
          ${isDone ? `<button class="ctrl-btn highlight open-btn" data-path="${task.filePath}">Open</button>` : ''}
          ${isDone ? `<button class="ctrl-btn share-btn" data-path="${task.filePath}">Share</button>` : ''}
          <button class="ctrl-btn redownload-btn" data-url="${task.url}" title="Download again in another format"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"></path></svg> Re-Download</button>
        </div>
      </div>
    `;

    const deleteBtn = card.querySelector('.task-delete-btn');
    if (deleteBtn) {
      deleteBtn.addEventListener('click', (ev) => {
        ev.stopPropagation();
        allTasks = allTasks.filter(t => t.id !== task.id);
        if (window.AndroidBridge && window.AndroidBridge.removeTask) {
          window.AndroidBridge.removeTask(task.id);
        }
        renderTasksList();
      });
    }

    const pauseBtn = card.querySelector('.pause-btn');
    if (pauseBtn) pauseBtn.addEventListener('click', () => window.AndroidBridge && window.AndroidBridge.pauseDownload(task.id));

    const resumeBtn = card.querySelector('.resume-btn');
    if (resumeBtn) resumeBtn.addEventListener('click', () => window.AndroidBridge && window.AndroidBridge.resumeDownload(task.id));

    const cancelBtn = card.querySelector('.cancel-btn');
    if (cancelBtn) cancelBtn.addEventListener('click', () => window.AndroidBridge && window.AndroidBridge.cancelDownload(task.id));

    const openBtn = card.querySelector('.open-btn');
    if (openBtn) openBtn.addEventListener('click', () => window.AndroidBridge && window.AndroidBridge.openFile(task.filePath));

    const shareBtn = card.querySelector('.share-btn');
    if (shareBtn) shareBtn.addEventListener('click', () => window.AndroidBridge && window.AndroidBridge.shareFile(task.filePath));

    const redownloadBtn = card.querySelector('.redownload-btn');
    if (redownloadBtn) redownloadBtn.addEventListener('click', () => {
      if (downloadsDrawer) downloadsDrawer.classList.remove('open');
      toggleAppMode('fetch');
      urlInput.value = task.url;
      handleFetchMedia();
      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast('Select resolution or format to download again');
      }
    });

    return card;
  }

  function updateQueueSpeedBanner() {
    const banner = document.getElementById('queueSpeedBanner');
    const countEl = document.getElementById('queueActiveCount');
    const speedEl = document.getElementById('queueTotalSpeed');
    if (!banner) return;

    const activeTasks = allTasks.filter(t => t.status === 'DOWNLOADING');
    if (activeTasks.length > 0) {
      banner.classList.remove('hidden');
      let totalSpeedBytes = 0;
      activeTasks.forEach(t => {
        if (t.speedBytesPerSec && t.speedBytesPerSec > 0) {
          totalSpeedBytes += t.speedBytesPerSec;
        }
      });
      if (countEl) countEl.textContent = `${activeTasks.length} Active Download${activeTasks.length > 1 ? 's' : ''}`;
      if (speedEl) {
        if (totalSpeedBytes > 0) {
          const mbSpeed = (totalSpeedBytes / (1024 * 1024)).toFixed(1);
          speedEl.textContent = `Total Speed: ${mbSpeed} MB/s`;
        } else {
          speedEl.textContent = 'Active Multi-Thread Queue';
        }
      }
    } else {
      banner.classList.add('hidden');
    }
  }

  function renderTasksList() {
    const activeTasks = allTasks.filter(t => t.status === 'DOWNLOADING' || t.status === 'QUEUED');
    if (activeDownloadsCount) activeDownloadsCount.classList.toggle('hidden', activeTasks.length === 0);
    if (drawerItemsCount) drawerItemsCount.textContent = `${allTasks.length} items`;
    if (emptyDownloadsState) emptyDownloadsState.classList.toggle('hidden', allTasks.length > 0);

    updateQueueSpeedBanner();

    // 1. Populate Popup Bottom Sheet Drawer
    if (downloadsTaskList) {
      const existingTaskElements = downloadsTaskList.querySelectorAll('.task-card-item');
      existingTaskElements.forEach(e => e.remove());
      allTasks.forEach(task => {
        downloadsTaskList.appendChild(buildTaskCardElement(task, true));
      });
    }

    // 2. Populate Full-Screen History View
    renderFullHistoryView();
  }

  function renderFullHistoryView() {
    const historyFullTaskList = document.getElementById('historyFullTaskList');
    const emptyHistoryFullState = document.getElementById('emptyHistoryFullState');
    const historyFullItemsCount = document.getElementById('historyFullItemsCount');

    if (historyFullItemsCount) {
      historyFullItemsCount.textContent = `${allTasks.length} files`;
    }

    if (!historyFullTaskList) return;

    // Filter tasks based on current category
    const filtered = allTasks.filter(task => {
      if (currentHistoryFilter === 'video') {
        return !task.quality?.isAudioOnly && !task.quality?.isImage;
      } else if (currentHistoryFilter === 'audio') {
        return !!task.quality?.isAudioOnly;
      } else if (currentHistoryFilter === 'image') {
        return !!task.quality?.isImage;
      }
      return true; // 'all'
    });

    if (emptyHistoryFullState) {
      emptyHistoryFullState.classList.toggle('hidden', filtered.length > 0);
    }

    const existingCards = historyFullTaskList.querySelectorAll('.task-card-item');
    existingCards.forEach(e => e.remove());

    filtered.forEach(task => {
      historyFullTaskList.appendChild(buildTaskCardElement(task, false));
    });
  }

  // Filter Chips in History View
  document.querySelectorAll('.history-filter-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      document.querySelectorAll('.history-filter-chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      currentHistoryFilter = chip.dataset.filter || 'all';
      renderFullHistoryView();
    });
  });

  function clearAllTasksAction() {
    if (allTasks.length === 0) {
      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast('Download history is already empty');
      }
      return;
    }
    const confirmClear = confirm('Are you sure you want to clear all download history?');
    if (confirmClear) {
      allTasks = [];
      if (window.AndroidBridge && window.AndroidBridge.clearAllTasks) {
        window.AndroidBridge.clearAllTasks();
      }
      renderTasksList();
    }
  }

  if (clearAllHistoryBtn) {
    clearAllHistoryBtn.addEventListener('click', clearAllTasksAction);
  }

  const historyClearAllBtn = document.getElementById('historyClearAllBtn');
  if (historyClearAllBtn) {
    historyClearAllBtn.addEventListener('click', clearAllTasksAction);
  }

  window.openDownloadsDrawer = function () {
    downloadsDrawer.classList.add('open');
  };

  window.focusUrlInput = function () {
    if (downloadsDrawer.classList.contains('open')) {
      downloadsDrawer.classList.remove('open');
    }
    urlInput.focus();
    urlInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  window.openTrimmer = function () {
    if (downloadsDrawer.classList.contains('open')) {
      downloadsDrawer.classList.remove('open');
    }
    const trimmerEl = document.getElementById('trimmerSection') || document.querySelector('.trim-controls');
    if (trimmerEl && !trimmerEl.classList.contains('hidden')) {
      trimmerEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
    } else {
      urlInput.focus();
      urlInput.scrollIntoView({ behavior: 'smooth', block: 'center' });
      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast('Paste video link above to open Trimmer Studio');
      }
    }
  };

  // =========================================================================
  // MEDIASEARCH STUDIO CONTROLLER & DUAL-ENGINE LOGIC
  // =========================================================================
  let currentAppMode = 'fetch'; // 'fetch' or 'search'
  const modeSwitchBtn = document.getElementById('modeSwitchBtn');
  const modeSwitchIcon = document.getElementById('modeSwitchIcon');
  const modeSwitchLabel = document.getElementById('modeSwitchLabel');
  const mediaFetchView = document.getElementById('mediaFetchView');
  const mediaSearchView = document.getElementById('mediaSearchView');

  const msSearchInput = document.getElementById('msSearchInput');
  const msClearSearchBtn = document.getElementById('msClearSearchBtn');
  const msSearchBtn = document.getElementById('msSearchBtn');
  const msSuggestionsDropdown = document.getElementById('msSuggestionsDropdown');
  const msMultiSelectToggle = document.getElementById('msMultiSelectToggle');
  const msMultiSelectCheck = document.getElementById('msMultiSelectCheck');
  const msPlatformPills = document.querySelectorAll('.ms-platform-pill');

  const msStatusBar = document.getElementById('msStatusBar');
  const msStatusText = document.getElementById('msStatusText');
  const msPlatformTags = document.getElementById('msPlatformTags');
  const msLoadingSkeleton = document.getElementById('msLoadingSkeleton');
  const msEmptyState = document.getElementById('msEmptyState');
  const msResultsGrid = document.getElementById('msResultsGrid');
  const msSuggestChips = document.querySelectorAll('.ms-suggest-chip');

  // Category Filter Tabs (Videos, Photos, All)
  const msCatTabs = document.querySelectorAll('.ms-cat-tab');
  let currentSearchCategory = 'all'; // 'all', 'video', 'photo'

  // Format & Quality Picker Bottom Sheet Modal Elements
  const searchFormatModal = document.getElementById('searchFormatModal');
  const sfmThumbnail = document.getElementById('sfmThumbnail');
  const sfmTitle = document.getElementById('sfmTitle');
  const sfmAuthor = document.getElementById('sfmAuthor');
  const sfmCloseBtn = document.getElementById('sfmCloseBtn');
  const sfmOptionsList = document.getElementById('sfmOptionsList');

  let isMultiSelectMode = false;
  let selectedPlatforms = new Set(['all']);
  let isSearchingMedia = false;
  let searchSuggestDebounce = null;
  let currentSearchResults = [];

  const mediaSettingsView = document.getElementById('mediaSettingsView');
  const mediaHistoryView = document.getElementById('mediaHistoryView');

  function toggleAppMode(targetMode) {
    if (targetMode) {
      currentAppMode = targetMode;
    } else {
      currentAppMode = (currentAppMode === 'fetch' ? 'search' : 'fetch');
    }

    if (currentAppMode === 'history') {
      mediaFetchView.classList.add('hidden');
      mediaSearchView.classList.add('hidden');
      if (mediaSettingsView) mediaSettingsView.classList.add('hidden');
      if (mediaHistoryView) mediaHistoryView.classList.remove('hidden');
      if (downloadsDrawer) downloadsDrawer.classList.remove('open');
      if (modeSwitchIcon) modeSwitchIcon.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>';
      if (modeSwitchLabel) modeSwitchLabel.textContent = 'Downloader';
      if (modeSwitchBtn) {
        modeSwitchBtn.classList.remove('active-search-mode');
        modeSwitchBtn.title = 'Switch back to Link Downloader';
      }
      renderFullHistoryView();
    } else if (currentAppMode === 'settings') {
      mediaFetchView.classList.add('hidden');
      mediaSearchView.classList.add('hidden');
      if (mediaHistoryView) mediaHistoryView.classList.add('hidden');
      if (mediaSettingsView) mediaSettingsView.classList.remove('hidden');
      if (modeSwitchIcon) modeSwitchIcon.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>';
      if (modeSwitchLabel) modeSwitchLabel.textContent = 'Downloader';
      if (modeSwitchBtn) {
        modeSwitchBtn.classList.remove('active-search-mode');
        modeSwitchBtn.title = 'Switch back to Link Downloader';
      }
      if (typeof refreshSettingsUI === 'function') refreshSettingsUI();
    } else if (currentAppMode === 'search') {
      mediaFetchView.classList.add('hidden');
      if (mediaSettingsView) mediaSettingsView.classList.add('hidden');
      if (mediaHistoryView) mediaHistoryView.classList.add('hidden');
      mediaSearchView.classList.remove('hidden');
      if (modeSwitchIcon) modeSwitchIcon.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>';
      if (modeSwitchLabel) modeSwitchLabel.textContent = 'Downloader';
      if (modeSwitchBtn) {
        modeSwitchBtn.classList.add('active-search-mode');
        modeSwitchBtn.title = 'Switch back to Link Downloader';
      }
      if (!msSearchInput.value) {
        setTimeout(() => msSearchInput.focus(), 150);
      }
    } else {
      currentAppMode = 'fetch';
      if (mediaSettingsView) mediaSettingsView.classList.add('hidden');
      if (mediaHistoryView) mediaHistoryView.classList.add('hidden');
      mediaSearchView.classList.add('hidden');
      mediaFetchView.classList.remove('hidden');
      if (modeSwitchIcon) modeSwitchIcon.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>';
      if (modeSwitchLabel) modeSwitchLabel.textContent = 'Search Studio';
      if (modeSwitchBtn) {
        modeSwitchBtn.classList.remove('active-search-mode');
        modeSwitchBtn.title = 'Switch to MediaSearch Studio';
      }
    }

    try {
      if (window.AndroidBridge && window.AndroidBridge.onAppModeChanged) {
        window.AndroidBridge.onAppModeChanged(currentAppMode);
      }
    } catch (_) {}

    // Sync Floating Bottom Nav Dock (Ref: media_1790638361612.png)
    try {
      document.querySelectorAll('#floatingBottomNav .floating-nav-btn').forEach(btn => {
        if (btn.getAttribute('data-mode') === currentAppMode) {
          btn.classList.add('active');
        } else {
          btn.classList.remove('active');
        }
      });
    } catch (_) {}
  }
  window.toggleAppMode = toggleAppMode;

  if (modeSwitchBtn) {
    modeSwitchBtn.addEventListener('click', () => {
      if (currentAppMode === 'settings' || currentAppMode === 'history') {
        toggleAppMode('fetch');
      } else {
        toggleAppMode();
      }
    });
  }

  // Category Filter Tabs Handler (Live Filter & Search Context)
  msCatTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      msCatTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentSearchCategory = tab.dataset.category || 'all';

      applyCategoryFilterAndRender();

      // If search input has text, automatically re-query if results were empty or user desires
      const query = msSearchInput.value.trim();
      if (query && currentSearchResults.length === 0) {
        executeMediaSearch(query);
      }
    });
  });

  function applyCategoryFilterAndRender() {
    if (!currentSearchResults || currentSearchResults.length === 0) return;

    let filtered = currentSearchResults;
    if (currentSearchCategory === 'video') {
      filtered = currentSearchResults.filter(it => it.mediaType === 'video');
    } else if (currentSearchCategory === 'photo') {
      filtered = currentSearchResults.filter(it => it.mediaType === 'image' || it.mediaType === 'photo' || it.mediaType === 'gif');
    }

    const catLabel = currentSearchCategory === 'all' ? 'media' : (currentSearchCategory === 'video' ? 'videos' : 'photos');
    msStatusText.textContent = `Showing ${filtered.length} ${catLabel} results`;
    renderMediaSearchResults(filtered);
  }

  // Multi-Select Toggle Handler
  if (msMultiSelectToggle) {
    msMultiSelectToggle.addEventListener('click', () => {
      isMultiSelectMode = !isMultiSelectMode;
      msMultiSelectToggle.classList.toggle('active', isMultiSelectMode);
      if (msMultiSelectToggle) {
        if (isMultiSelectMode) {
          msMultiSelectToggle.classList.add('active');
          if (msMultiSelectCheck) msMultiSelectCheck.innerHTML = '<rect x="3" y="3" width="18" height="18" rx="4" fill="currentColor"></rect><polyline points="9 12 11 14 15 10" stroke="#000" stroke-width="2.5" fill="none"></polyline>';
        } else {
          msMultiSelectToggle.classList.remove('active');
          if (msMultiSelectCheck) msMultiSelectCheck.innerHTML = '<rect x="3" y="3" width="18" height="18" rx="4"></rect>';
        }
      }

      if (!isMultiSelectMode) {
        // Reset to single select: pick the first selected or all
        const first = Array.from(selectedPlatforms)[0] || 'all';
        selectedPlatforms.clear();
        selectedPlatforms.add(first);
        updatePlatformPillsUI();
      }

      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast(
          isMultiSelectMode 
            ? 'Multi-Filter ON: Tap platforms to select multiple engines!' 
            : 'Single Mode: Tap any platform to search that engine only.'
        );
      }
    });
  }

  // Platform Filter Pills Handler
  msPlatformPills.forEach(pill => {
    pill.addEventListener('click', () => {
      const plat = pill.dataset.platform;

      if (!isMultiSelectMode) {
        // Single select mode
        selectedPlatforms.clear();
        selectedPlatforms.add(plat);
      } else {
        // Multi-select mode
        if (plat === 'all') {
          selectedPlatforms.clear();
          selectedPlatforms.add('all');
        } else {
          selectedPlatforms.delete('all');
          if (selectedPlatforms.has(plat)) {
            selectedPlatforms.delete(plat);
          } else {
            selectedPlatforms.add(plat);
          }
          if (selectedPlatforms.size === 0) {
            selectedPlatforms.add('all');
          }
        }
      }

      updatePlatformPillsUI();

      const query = msSearchInput.value.trim();
      if (query) {
        executeMediaSearch(query);
      }
    });
  });

  function updatePlatformPillsUI() {
    msPlatformPills.forEach(pill => {
      const plat = pill.dataset.platform;
      const isSelected = selectedPlatforms.has(plat);
      pill.classList.toggle('active', isSelected && !isMultiSelectMode);
      pill.classList.toggle('multi-active', isSelected && isMultiSelectMode);
    });
  }

  // Search Input & Clear Button
  if (msSearchInput) {
    msSearchInput.addEventListener('input', () => {
      const val = msSearchInput.value.trim();
      msClearSearchBtn.classList.toggle('hidden', !val);

      // Trigger Google/YouTube Search Autocomplete suggestions with debouncing
      clearTimeout(searchSuggestDebounce);
      if (val.length >= 2) {
        searchSuggestDebounce = setTimeout(() => {
          if (window.AndroidBridge && window.AndroidBridge.getSearchSuggestions) {
            window.AndroidBridge.getSearchSuggestions(val);
          }
        }, 220);
      } else {
        msSuggestionsDropdown.classList.add('hidden');
      }
    });

    msSearchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        msSuggestionsDropdown.classList.add('hidden');
        const query = msSearchInput.value.trim();
        if (query) executeMediaSearch(query);
      }
    });
  }

  if (msClearSearchBtn) {
    msClearSearchBtn.addEventListener('click', () => {
      msSearchInput.value = '';
      msClearSearchBtn.classList.add('hidden');
      msSuggestionsDropdown.classList.add('hidden');
      msSearchInput.focus();
    });
  }

  if (msSearchBtn) {
    msSearchBtn.addEventListener('click', () => {
      msSuggestionsDropdown.classList.add('hidden');
      const query = msSearchInput.value.trim();
      if (query) executeMediaSearch(query);
    });
  }

  // Suggestion Chips
  msSuggestChips.forEach(chip => {
    chip.addEventListener('click', () => {
      const query = chip.textContent.trim();
      msSearchInput.value = query;
      msClearSearchBtn.classList.remove('hidden');
      executeMediaSearch(query);
    });
  });

  // Autocomplete Suggestions Loader Callback from AndroidBridge
  window.onSearchSuggestionsLoaded = function (suggestions) {
    if (!suggestions || suggestions.length === 0 || !msSearchInput.value.trim()) {
      msSuggestionsDropdown.classList.add('hidden');
      return;
    }

    msSuggestionsDropdown.innerHTML = '';
    suggestions.forEach(item => {
      const row = document.createElement('div');
      row.className = 'suggestion-item';
      row.innerHTML = `
        <span class="suggestion-icon"><svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg></span>
        <span class="suggestion-text">${item}</span>
      `;
      row.addEventListener('click', () => {
        msSearchInput.value = item;
        msSuggestionsDropdown.classList.add('hidden');
        msClearSearchBtn.classList.remove('hidden');
        executeMediaSearch(item);
      });
      msSuggestionsDropdown.appendChild(row);
    });

    msSuggestionsDropdown.classList.remove('hidden');
  };

  document.addEventListener('click', (ev) => {
    if (msSuggestionsDropdown && !msSuggestionsDropdown.contains(ev.target) && ev.target !== msSearchInput) {
      msSuggestionsDropdown.classList.add('hidden');
    }
  });

  // Execute Search
  function executeMediaSearch(query) {
    if (isSearchingMedia) return;
    isSearchingMedia = true;

    msSuggestionsDropdown.classList.add('hidden');
    msEmptyState.classList.add('hidden');
    msResultsGrid.classList.add('hidden');
    msLoadingSkeleton.classList.remove('hidden');
    msStatusBar.classList.add('hidden');

    const platformsArray = Array.from(selectedPlatforms);
    // If 'all' platforms and a category filter is active, route through category
    if (platformsArray.includes('all') && currentSearchCategory !== 'all') {
      platformsArray.length = 0;
      platformsArray.push(currentSearchCategory);
    }

    if (window.AndroidBridge && window.AndroidBridge.performMediaSearch) {
      window.AndroidBridge.performMediaSearch(query, JSON.stringify(platformsArray), 1);
    }
  }

  // Callback when Native Search Results Arrive
  window.onSearchResultsLoaded = function (results) {
    isSearchingMedia = false;
    msLoadingSkeleton.classList.add('hidden');
    currentSearchResults = results || [];

    if (currentSearchResults.length === 0) {
      msEmptyState.classList.remove('hidden');
      msResultsGrid.classList.add('hidden');
      msStatusBar.classList.add('hidden');
      return;
    }

    msStatusBar.classList.remove('hidden');
    applyCategoryFilterAndRender();
  };

  window.onSearchResultsError = function (errMsg) {
    isSearchingMedia = false;
    msLoadingSkeleton.classList.add('hidden');
    msEmptyState.classList.remove('hidden');
    if (window.AndroidBridge && window.AndroidBridge.showToast) {
      window.AndroidBridge.showToast(errMsg || 'Search error');
    }
  };

  function renderMediaSearchResults(items) {
    msResultsGrid.innerHTML = '';

    items.forEach(item => {
      const card = document.createElement('div');
      card.className = 'ms-card';

      const thumbUrl = item.thumbnail || 'https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=400';
      const isVideo = item.mediaType === 'video';

      // Video Timeline / Duration badge on thumbnail
      const durationBadgeHtml = isVideo
        ? `<span class="ms-badge-duration"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg> ${item.durationFormatted || 'HD'}</span>`
        : `<span class="ms-badge-duration">${item.resolutionBadge || 'Photo'}</span>`;

      card.innerHTML = `
        <div class="ms-card-thumb-wrap">
          <img src="${thumbUrl}" class="ms-card-thumb" alt="" loading="lazy" />
          <span class="ms-badge-platform">${item.platform}</span>
          ${durationBadgeHtml}
        </div>
        <div class="ms-card-content">
          <div class="ms-card-title">${item.title}</div>
          <div class="ms-card-author">${item.author}</div>
          <div class="ms-card-actions">
            <button class="ms-btn-download" title="Select Download Quality & Format">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.2" class="pill-svg">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                <polyline points="7 10 12 15 17 10"></polyline>
                <line x1="12" y1="15" x2="12" y2="3"></line>
              </svg>
              <span>Download</span>
            </button>
            ${isVideo ? `<button class="ms-btn-trim" title="Trim Video Clip">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg">
                <circle cx="6" cy="6" r="3"></circle><circle cx="6" cy="18" r="3"></circle>
                <line x1="20" y1="4" x2="8.12" y2="15.88"></line><line x1="14.47" y1="14.48" x2="20" y2="20"></line><line x1="8.12" y1="8.12" x2="12" y2="12"></line>
              </svg>
              <span>Trim</span>
            </button>` : ''}
          </div>
        </div>
      `;

      const dlBtn = card.querySelector('.ms-btn-download');
      dlBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        openSearchFormatModal(item);
      });

      const trimBtn = card.querySelector('.ms-btn-trim');
      if (trimBtn) {
        trimBtn.addEventListener('click', (e) => {
          e.stopPropagation();
          bridgeSearchItemToTrimmer(item);
        });
      }

      msResultsGrid.appendChild(card);
    });

    msResultsGrid.classList.remove('hidden');
  }

  // Format & Quality Picker Bottom Sheet Modal Logic
  if (sfmCloseBtn) {
    sfmCloseBtn.addEventListener('click', closeSearchFormatModal);
  }
  if (searchFormatModal) {
    searchFormatModal.addEventListener('click', (e) => {
      if (e.target === searchFormatModal) closeSearchFormatModal();
    });
  }

  function closeSearchFormatModal() {
    if (searchFormatModal) searchFormatModal.classList.add('hidden');
  }

  function openSearchFormatModal(item) {
    if (!searchFormatModal || !sfmOptionsList) return;

    sfmThumbnail.src = item.thumbnail || 'https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=400';
    sfmTitle.textContent = item.title;
    sfmAuthor.textContent = `${item.author} • ${item.platform}`;

    sfmOptionsList.innerHTML = '';

    const isVideo = item.mediaType === 'video';
    const isAudio = item.mediaType === 'audio';
    const isImage = item.mediaType === 'image' || item.mediaType === 'photo' || item.mediaType === 'gif';
    const targetUrl = item.directUrl || item.pageUrl || '';
    const isSocialOrYt = targetUrl.includes('youtube.com') ||
      targetUrl.includes('youtu.be') ||
      targetUrl.includes('tiktok.com') ||
      targetUrl.includes('instagram.com') ||
      targetUrl.includes('twitter.com') ||
      targetUrl.includes('x.com');

    const options = [];

    if (isVideo) {
      if (isSocialOrYt) {
        options.push(
          { format: '1080p', label: '1080p Full HD Video', sub: 'MP4 • Highest Quality Video & Audio', badge: '1080p MP4', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"></rect><line x1="7" y1="2" x2="7" y2="22"></line><line x1="17" y1="2" x2="17" y2="22"></line><line x1="2" y1="12" x2="22" y2="12"></line></svg>', iconClass: '' },
          { format: '720p', label: '720p HD Video', sub: 'MP4 • Balanced Size & Fast Download', badge: '720p MP4', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon></svg>', iconClass: '' },
          { format: '480p', label: '480p SD Video', sub: 'MP4 • Compact Size (Data Saver)', badge: '480p MP4', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"></rect><line x1="12" y1="18" x2="12.01" y2="18"></line></svg>', iconClass: '' },
          { format: 'mp3', label: 'Extract Audio (MP3)', sub: 'MP3 • High Bitrate Clean Audio Track', badge: '320k MP3', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>', iconClass: 'audio-icon' }
        );
      } else {
        options.push(
          { format: 'best', label: 'Original 4K / HD Video', sub: `${item.resolutionBadge || 'HD Media'} • Direct Stream`, badge: 'Full Video', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"></rect><line x1="7" y1="2" x2="7" y2="22"></line><line x1="17" y1="2" x2="17" y2="22"></line><line x1="2" y1="12" x2="22" y2="12"></line></svg>', iconClass: '' },
          { format: 'mp3', label: 'Extract Audio (MP3)', sub: 'MP3 • Audio Track Only', badge: 'HQ MP3', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>', iconClass: 'audio-icon' }
        );
      }
    } else if (isAudio) {
      options.push(
        { format: 'audio', label: 'Download HQ Audio Track', sub: `${item.durationFormatted || 'Audio'} • MP3 Master File`, badge: 'HQ MP3', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>', iconClass: 'audio-icon' }
      );
    } else if (isImage) {
      const isGifItem = item.mediaType === 'gif' || targetUrl.toLowerCase().includes('.gif') || (item.title && item.title.toLowerCase().includes('gif'));
      if (isGifItem) {
        options.push(
          { format: 'gif', label: 'Animated GIF (Original Motion)', sub: 'Infinite Loop • High Quality GIF', badge: 'GIF Animation', icon: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon></svg>', iconClass: 'image-icon' }
        );
      }
      options.push(
        { format: 'image', label: isGifItem ? 'Original GIF File' : 'Download High-Res Original', sub: `${item.resolutionBadge || 'Full Resolution'} • ${isGifItem ? 'Animated GIF' : 'Original JPG/PNG'}`, badge: isGifItem ? 'GIF' : 'High Res', icon: isGifItem ? 'GIF' : '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>', iconClass: 'image-icon' }
      );
    }

    options.forEach(opt => {
      const row = document.createElement('div');
      row.className = 'format-option-card';
      row.innerHTML = `
        <div class="format-option-left">
          <div class="format-option-icon ${opt.iconClass}">${opt.icon}</div>
          <div class="format-option-text">
            <span class="format-option-label">${opt.label}</span>
            <span class="format-option-sub">${opt.sub}</span>
          </div>
        </div>
        <span class="format-option-badge ${opt.iconClass.includes('audio') ? 'audio-badge' : (opt.iconClass.includes('image') ? 'image-badge' : '')}">${opt.badge}</span>
      `;
      row.addEventListener('click', () => {
        closeSearchFormatModal();
        startFormattedDownload(item, opt.format);
      });
      sfmOptionsList.appendChild(row);
    });

    searchFormatModal.classList.remove('hidden');
  }

  function startFormattedDownload(item, format) {
    if (window.AndroidBridge && window.AndroidBridge.downloadSearchResultWithFormat) {
      window.AndroidBridge.downloadSearchResultWithFormat(JSON.stringify(item), format);
      downloadsDrawer.classList.add('open');
    } else if (window.AndroidBridge && window.AndroidBridge.downloadSearchResult) {
      window.AndroidBridge.downloadSearchResult(JSON.stringify(item));
      downloadsDrawer.classList.add('open');
    }
  }

  function bridgeSearchItemToTrimmer(item) {
    const targetUrl = item.directUrl || item.pageUrl;
    if (!targetUrl) return;

    // Switch back to Downloader mode
    toggleAppMode('fetch');

    // Populate URL input and trigger media resolution
    urlInput.value = targetUrl;
    handleFetchMedia();

    // Scroll to trimmer once media is resolved
    setTimeout(() => {
      const trimmerSection = document.getElementById('trimmerSection') || document.querySelector('.trim-controls');
      if (trimmerSection) {
        trimmerSection.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    }, 600);

    if (window.AndroidBridge && window.AndroidBridge.showToast) {
      window.AndroidBridge.showToast(`Loaded "${item.title}" into Clip Trimmer!`);
    }
  }

  // =========================================================================
  // SETTINGS STUDIO CONTROLLER
  // =========================================================================
  const settingsCacheSizeVal = document.getElementById('settingsCacheSizeVal');
  const settingsClearCacheBtn = document.getElementById('settingsClearCacheBtn');
  const settingsCacheLimitSelect = document.getElementById('settingsCacheLimitSelect');
  const saveCustomThemeBtn = document.getElementById('saveCustomThemeBtn');
  const userCustomPresetsRow = document.getElementById('userCustomPresetsRow');
  const userCustomPresetsContainer = document.getElementById('userCustomPresetsContainer');
  const settingsDefaultVideoRes = document.getElementById('settingsDefaultVideoRes');
  const settingsDefaultAudioFmt = document.getElementById('settingsDefaultAudioFmt');
  const settingsDefaultImgFmt = document.getElementById('settingsDefaultImgFmt');
  const settingsSaveDirPath = document.getElementById('settingsSaveDirPath');
  const settingsSubfoldersToggle = document.getElementById('settingsSubfoldersToggle');
  const settingsMaxConcurrentSelect = document.getElementById('settingsMaxConcurrentSelect');
  const settingsFilenameTemplateInput = document.getElementById('settingsFilenameTemplateInput');
  const filenamePreviewText = document.getElementById('filenamePreviewText');
  const settingsCookiesInput = document.getElementById('settingsCookiesInput');
  const cookiesStatusBadge = document.getElementById('cookiesStatusBadge');
  const saveCookiesBtn = document.getElementById('saveCookiesBtn');
  const clearCookiesBtn = document.getElementById('clearCookiesBtn');
  const cookieGuideBtn = document.getElementById('cookieGuideBtn');
  const autoFormatCookiesBtn = document.getElementById('autoFormatCookiesBtn');
  const cookieGuideModal = document.getElementById('cookieGuideModal');
  const closeCookieGuideBtn = document.getElementById('closeCookieGuideBtn');
  const btnDoneCookieGuide = document.getElementById('btnDoneCookieGuide');
  const quickAutoAdjustInput = document.getElementById('quickAutoAdjustInput');
  const btnRunAutoAdjust = document.getElementById('btnRunAutoAdjust');
  const manualCheckUpdateBtn = document.getElementById('manualCheckUpdateBtn');

  // In-App APK Updater Modal Elements
  const inAppUpdateModal = document.getElementById('inAppUpdateModal');
  const updateVersionBadge = document.getElementById('updateVersionBadge');
  const updateChangelogList = document.getElementById('updateChangelogList');
  const updateDownloadProgressWrap = document.getElementById('updateDownloadProgressWrap');
  const updatePercentText = document.getElementById('updatePercentText');
  const updateProgressFill = document.getElementById('updateProgressFill');
  const btnUpdateNow = document.getElementById('btnUpdateNow');
  const btnUpdateRemindLater = document.getElementById('btnUpdateRemindLater');
  const btnUpdateSkip = document.getElementById('btnUpdateSkip');
  let activeUpdateInfo = null;

  function formatBytesDisplay(bytes) {
    if (!bytes || bytes <= 0) return '0.0 MB';
    const mb = bytes / (1024 * 1024);
    if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
    if (mb >= 0.1) return mb.toFixed(1) + ' MB';
    return (bytes / 1024).toFixed(0) + ' KB';
  }

  function refreshCacheSize() {
    if (!settingsCacheSizeVal) return;
    try {
      if (window.AndroidBridge && window.AndroidBridge.getAppCacheSize) {
        settingsCacheSizeVal.textContent = window.AndroidBridge.getAppCacheSize();
      } else if (window.AndroidBridge && window.AndroidBridge.getAppCacheSizeBytes) {
        const bytes = window.AndroidBridge.getAppCacheSizeBytes();
        settingsCacheSizeVal.textContent = formatBytesDisplay(bytes);
      } else {
        let lsTotal = 0;
        for (let x in localStorage) {
          if (localStorage.hasOwnProperty(x)) {
            lsTotal += (localStorage[x].length * 2);
          }
        }
        settingsCacheSizeVal.textContent = formatBytesDisplay(lsTotal + 4.2 * 1024 * 1024);
      }
    } catch (_) {
      settingsCacheSizeVal.textContent = '0.0 MB';
    }
  }

  function getStudioInputsTheme() {
    const p1 = document.getElementById('pickerPrimary')?.value || '#0A84FF';
    const p2 = document.getElementById('pickerSecondary')?.value || '#0060DF';
    const bg = document.getElementById('pickerBg')?.value || '#000000';
    const surface = document.getElementById('pickerSurface')?.value || '#121214';
    return {
      id: 'custom',
      name: 'Custom',
      primary: p1,
      secondary: p2,
      bg: bg,
      surface: surface,
      card: surface === '#000000' ? '#121214' : surface
    };
  }

  function renderUserCustomThemes() {
    if (!userCustomPresetsContainer || !userCustomPresetsRow) return;
    try {
      const list = JSON.parse(localStorage.getItem('mediafetch_user_custom_themes') || '[]');
      if (!list || list.length === 0) {
        userCustomPresetsRow.classList.add('hidden');
        userCustomPresetsContainer.innerHTML = '';
        return;
      }
      userCustomPresetsRow.classList.remove('hidden');
      userCustomPresetsContainer.innerHTML = '';
      list.forEach((t, idx) => {
        const chip = document.createElement('div');
        chip.className = 'custom-preset-chip';
        chip.innerHTML = `
          <span class="custom-preset-dot" style="background:linear-gradient(135deg, ${t.primary}, ${t.secondary});"></span>
          <span>${t.name || ('Theme #' + (idx + 1))}</span>
          <span class="custom-preset-del" title="Delete theme">&times;</span>
        `;
        chip.addEventListener('click', (e) => {
          if (e.target.classList.contains('custom-preset-del')) {
            e.stopPropagation();
            list.splice(idx, 1);
            localStorage.setItem('mediafetch_user_custom_themes', JSON.stringify(list));
            renderUserCustomThemes();
          } else {
            applyFullTheme(t, true);
          }
        });
        userCustomPresetsContainer.appendChild(chip);
      });
    } catch (_) {}
  }

  function refreshCookiesBadge() {
    try {
      if (window.AndroidBridge && window.AndroidBridge.getCookiesCount) {
        const count = window.AndroidBridge.getCookiesCount();
        if (cookiesStatusBadge) {
          cookiesStatusBadge.textContent = count > 0 ? `${count} cookies active` : 'No cookies loaded';
          cookiesStatusBadge.style.color = count > 0 ? '#30d158' : '';
        }
      }
    } catch (_) {}
  }

  function refreshInstalledVersion() {
    try {
      if (window.AndroidBridge && window.AndroidBridge.getAppVersionName) {
        const ver = window.AndroidBridge.getAppVersionName();
        if (ver) {
          const badge = document.getElementById('installedVersionBadge');
          if (badge) badge.textContent = `v${ver} (Official)`;
          const footer = document.getElementById('appVersionFooter');
          if (footer) footer.textContent = `MediaFetch Mobile v${ver} • Built with High-Performance Android Native Core`;
        }
      }
    } catch (_) {}
  }

  function refreshSettingsUI() {
    refreshCacheSize();
    refreshCookiesBadge();
    refreshInstalledVersion();

    if (settingsSaveDirPath) {
      try {
        if (window.AndroidBridge && window.AndroidBridge.getDownloadDirectoryPath) {
          settingsSaveDirPath.textContent = window.AndroidBridge.getDownloadDirectoryPath();
        }
      } catch (_) {}
    }
  }

  function initSettingsController() {
    try {
      const savedLimit = localStorage.getItem('mediafetch_cache_limit') || '250';
      if (settingsCacheLimitSelect) settingsCacheLimitSelect.value = savedLimit;

      const savedVid = localStorage.getItem('mediafetch_default_video_res') || 'auto';
      if (settingsDefaultVideoRes) settingsDefaultVideoRes.value = savedVid;

      const savedAud = localStorage.getItem('mediafetch_default_audio_fmt') || 'mp3_320';
      if (settingsDefaultAudioFmt) settingsDefaultAudioFmt.value = savedAud;

      const savedImg = localStorage.getItem('mediafetch_default_img_fmt') || 'original';
      if (settingsDefaultImgFmt) settingsDefaultImgFmt.value = savedImg;

      const savedSubfolders = localStorage.getItem('mediafetch_organize_subfolders');
      if (settingsSubfoldersToggle && savedSubfolders !== null) {
        settingsSubfoldersToggle.checked = (savedSubfolders === 'true');
      }

      renderUserCustomThemes();
    } catch (_) {}

    if (settingsClearCacheBtn) {
      settingsClearCacheBtn.addEventListener('click', () => {
        try {
          if (window.AndroidBridge && window.AndroidBridge.clearAppCache) {
            window.AndroidBridge.clearAppCache();
          }
        } catch (_) {}

        refreshCacheSize();
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('App cache cleared successfully!');
        }
      });
    }

    if (settingsCacheLimitSelect) {
      settingsCacheLimitSelect.addEventListener('change', (e) => {
        localStorage.setItem('mediafetch_cache_limit', e.target.value);
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(`Cache limit set to ${e.target.options[e.target.selectedIndex].text}`);
        }
      });
    }

    // Visual Surface & Glassmorphic Style selection
    document.querySelectorAll('.visual-style-card').forEach(card => {
      card.addEventListener('click', () => {
        const style = card.getAttribute('data-style');
        if (style) {
          applyVisualStyle(style);
          if (window.AndroidBridge && window.AndroidBridge.showToast) {
            const names = {
              'solid': 'Switched to Pure Solid AMOLED theme',
              'glass': 'Switched to Liquid Glassmorphism theme',
              'prism': 'Switched to Cyber Aurora Prism theme'
            };
            window.AndroidBridge.showToast(names[style] || 'Theme updated');
          }
        }
      });
    });

    // Curated Complete Themes selection
    document.querySelectorAll('.theme-preset-card').forEach(card => {
      card.addEventListener('click', () => {
        const presetKey = card.dataset.preset;
        if (presetKey && CURATED_THEMES[presetKey]) {
          applyFullTheme(CURATED_THEMES[presetKey], true);
          if (window.AndroidBridge && window.AndroidBridge.showToast) {
            window.AndroidBridge.showToast(`Applied ${CURATED_THEMES[presetKey].name} theme`);
          }
        }
      });
    });

    // Advanced Theme Studio Toggle
    const toggleAdvancedThemeStudio = document.getElementById('toggleAdvancedThemeStudio');
    const advancedThemeControlsContainer = document.getElementById('advancedThemeControlsContainer');

    if (toggleAdvancedThemeStudio && advancedThemeControlsContainer) {
      const isAdvEnabled = localStorage.getItem('mediafetch_advanced_theme_enabled') === 'true';
      toggleAdvancedThemeStudio.checked = isAdvEnabled;
      advancedThemeControlsContainer.classList.toggle('hidden', !isAdvEnabled);

      toggleAdvancedThemeStudio.addEventListener('change', (e) => {
        const enabled = e.target.checked;
        localStorage.setItem('mediafetch_advanced_theme_enabled', enabled ? 'true' : 'false');
        advancedThemeControlsContainer.classList.toggle('hidden', !enabled);
        if (enabled) {
          setTimeout(() => {
            advancedThemeControlsContainer.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
          }, 50);
        }
      });
    }

    // Initialize Modern 2D Color Picker Studio (Ae / Pr Style)
    initColorPickerStudio();

    // Auto-match gradient button
    const autoMatchBtn = document.getElementById('autoMatchGradientBtn');
    if (autoMatchBtn) {
      autoMatchBtn.addEventListener('click', () => {
        const primary = (currentActiveTheme && currentActiveTheme.primary) || '#0A84FF';
        const secondary = autoMatchSecondaryColor(primary);
        if (!currentActiveTheme) currentActiveTheme = { ...CURATED_THEMES.blue };
        currentActiveTheme.secondary = secondary;
        currentActiveTheme.id = 'custom';
        applyFullTheme(currentActiveTheme, true);
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('Harmonized gradient auto-matched!');
        }
      });
    }

    // Quick surface & dual background chips
    document.querySelectorAll('.quick-color-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const target = chip.dataset.target;
        const color = chip.dataset.color;
        if (!color) return;
        if (!currentActiveTheme) currentActiveTheme = { ...CURATED_THEMES.blue };

        if (target === 'bg') {
          currentActiveTheme.bg = color;
          currentActiveTheme.bgEnd = chip.dataset.end || color;
        } else if (target === 'surface') {
          currentActiveTheme.surface = color;
          currentActiveTheme.card = color;
        }
        currentActiveTheme.id = 'custom';
        applyFullTheme(currentActiveTheme, true);
      });
    });

    // Save custom preset
    if (saveCustomThemeBtn) {
      saveCustomThemeBtn.addEventListener('click', () => {
        const cur = getStudioInputsTheme();
        try {
          const list = JSON.parse(localStorage.getItem('mediafetch_user_custom_themes') || '[]');
          cur.id = 'custom_' + Date.now();
          cur.name = `${cur.primary.toUpperCase()} Duo`;
          list.unshift(cur);
          if (list.length > 8) list.pop();
          localStorage.setItem('mediafetch_user_custom_themes', JSON.stringify(list));
          renderUserCustomThemes();
          if (window.AndroidBridge && window.AndroidBridge.showToast) {
            window.AndroidBridge.showToast('Custom theme preset saved!');
          }
        } catch (_) {}
      });
    }

    // Reset default button
    const resetThemeDefaultBtn = document.getElementById('resetThemeDefaultBtn');
    if (resetThemeDefaultBtn) {
      resetThemeDefaultBtn.addEventListener('click', () => {
        applyFullTheme(CURATED_THEMES.blue, true);
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('Reset to Cyber Blue default');
        }
      });
    }

    if (settingsDefaultVideoRes) {
      settingsDefaultVideoRes.addEventListener('change', (e) => {
        localStorage.setItem('mediafetch_default_video_res', e.target.value);
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(`Default video quality set to ${e.target.options[e.target.selectedIndex].text}`);
        }
      });
    }

    if (settingsDefaultAudioFmt) {
      settingsDefaultAudioFmt.addEventListener('change', (e) => {
        localStorage.setItem('mediafetch_default_audio_fmt', e.target.value);
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(`Default audio format set to ${e.target.options[e.target.selectedIndex].text}`);
        }
      });
    }

    if (settingsDefaultImgFmt) {
      settingsDefaultImgFmt.addEventListener('change', (e) => {
        localStorage.setItem('mediafetch_default_img_fmt', e.target.value);
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(`Default image export set to ${e.target.options[e.target.selectedIndex].text}`);
        }
      });
    }

    if (settingsSubfoldersToggle) {
      settingsSubfoldersToggle.addEventListener('change', (e) => {
        localStorage.setItem('mediafetch_organize_subfolders', e.target.checked ? 'true' : 'false');
      });
    }

    // Feature 5: Max Concurrent Downloads Handler
    if (settingsMaxConcurrentSelect) {
      try {
        if (window.AndroidBridge && window.AndroidBridge.getMaxConcurrentDownloads) {
          settingsMaxConcurrentSelect.value = String(window.AndroidBridge.getMaxConcurrentDownloads());
        }
      } catch (_) {}
      settingsMaxConcurrentSelect.addEventListener('change', (e) => {
        const count = parseInt(e.target.value) || 2;
        if (window.AndroidBridge && window.AndroidBridge.saveMaxConcurrentDownloads) {
          window.AndroidBridge.saveMaxConcurrentDownloads(count);
        }
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(`Max parallel downloads set to ${count}`);
        }
      });
    }

    // Feature 6: Custom Filename Pattern Preview & Chips
    function updateFilenamePreview(tmpl) {
      if (!filenamePreviewText) return;
      const t = tmpl || '{title} [{quality}]';
      const now = new Date();
      const dateStr = now.toISOString().split('T')[0];
      let preview = t
        .replace(/\{title\}/gi, 'Cyberpunk 2077 Night City')
        .replace(/\{author\}/gi, '@CDProjektRed')
        .replace(/\{quality\}/gi, '1080p')
        .replace(/\{resolution\}/gi, '1920x1080')
        .replace(/\{date\}/gi, dateStr)
        .replace(/\{platform\}/gi, 'YouTube')
        .replace(/\{ext\}/gi, 'mp4');
      if (!preview.includes('.')) preview += '.mp4';
      filenamePreviewText.textContent = preview;
    }

    if (settingsFilenameTemplateInput) {
      try {
        if (window.AndroidBridge && window.AndroidBridge.getFilenameTemplate) {
          settingsFilenameTemplateInput.value = window.AndroidBridge.getFilenameTemplate();
        }
      } catch (_) {}
      updateFilenamePreview(settingsFilenameTemplateInput.value);

      settingsFilenameTemplateInput.addEventListener('input', (e) => {
        const val = e.target.value.trim() || '{title} [{quality}]';
        updateFilenamePreview(val);
        if (window.AndroidBridge && window.AndroidBridge.saveFilenameTemplate) {
          window.AndroidBridge.saveFilenameTemplate(val);
        }
      });
    }

    const filenameTagChips = document.querySelectorAll('.filename-tag-chip');
    filenameTagChips.forEach(chip => {
      chip.addEventListener('click', () => {
        if (!settingsFilenameTemplateInput) return;
        const tag = chip.getAttribute('data-tag');
        if (!tag) return;
        settingsFilenameTemplateInput.value = (settingsFilenameTemplateInput.value + ' ' + tag).trim();
        updateFilenamePreview(settingsFilenameTemplateInput.value);
        if (window.AndroidBridge && window.AndroidBridge.saveFilenameTemplate) {
          window.AndroidBridge.saveFilenameTemplate(settingsFilenameTemplateInput.value);
        }
      });
    });

    // Feature 3: Netscape / Session Account Cookies Controller
    if (settingsCookiesInput) {
      try {
        if (window.AndroidBridge && window.AndroidBridge.getCustomCookies) {
          settingsCookiesInput.value = window.AndroidBridge.getCustomCookies();
        }
      } catch (_) {}
      refreshCookiesBadge();
    }

    if (saveCookiesBtn) {
      saveCookiesBtn.addEventListener('click', () => {
        const val = settingsCookiesInput ? settingsCookiesInput.value.trim() : '';
        if (!val) {
          if (window.AndroidBridge && window.AndroidBridge.showToast) {
            window.AndroidBridge.showToast('Please paste cookies first');
          }
          return;
        }
        if (window.AndroidBridge && window.AndroidBridge.saveCustomCookies) {
          window.AndroidBridge.saveCustomCookies(val);
        }
        refreshCookiesBadge();
      });
    }

    if (clearCookiesBtn) {
      clearCookiesBtn.addEventListener('click', () => {
        if (settingsCookiesInput) settingsCookiesInput.value = '';
        if (window.AndroidBridge && window.AndroidBridge.clearCustomCookies) {
          window.AndroidBridge.clearCustomCookies();
        }
        refreshCookiesBadge();
      });
    }

    // Smart Cookie Auto-Adjuster Parser
    function cleanAndFormatCookies(raw) {
      if (!raw || typeof raw !== 'string') return '';
      let text = raw.trim();
      if (!text) return '';

      // 1. JSON Array from extension (e.g. Cookie-Editor: [{"name": "...", "value": "..."}])
      if (text.startsWith('[') && text.endsWith(']')) {
        try {
          const arr = JSON.parse(text);
          if (Array.isArray(arr)) {
            const pairs = [];
            arr.forEach(item => {
              if (item && item.name && item.value) {
                pairs.push(`${item.name.trim()}=${item.value.trim()}`);
              }
            });
            if (pairs.length > 0) return pairs.join('; ') + ';';
          }
        } catch (_) {}
      }

      // 2. Netscape format (tab-delimited lines)
      if (text.includes('\t')) {
        const lines = text.split('\n');
        const pairs = [];
        lines.forEach(line => {
          const l = line.trim();
          if (!l || l.startsWith('#')) return;
          const cols = l.split('\t');
          if (cols.length >= 7) {
            const k = cols[5].trim();
            const v = cols[6].trim();
            if (k && v) pairs.push(`${k}=${v}`);
          }
        });
        if (pairs.length > 0) return pairs.join('; ') + ';';
      }

      // 3. Raw Request Headers / Key-Value lines
      let cleaned = text.replace(/^cookie:\s*/i, '');
      // Translate colloquial labels like "SID : xxx" or "INSTA : yyy"
      cleaned = cleaned.replace(/^(?:SID|LOGIN|LOGIN_INFO|INSTA|INSTAGRAM|SESSIONID)\s*[:=]\s*/gim, (match) => {
        const upper = match.trim().toUpperCase().replace(/[:=]/g, '').trim();
        if (upper === 'INSTA' || upper === 'INSTAGRAM') return 'sessionid=';
        if (upper === 'LOGIN') return 'LOGIN_INFO=';
        return upper + '=';
      });

      const tokens = cleaned.split(/[\r\n;]+/);
      const results = [];
      const seen = new Set();
      tokens.forEach(tok => {
        const t = tok.trim();
        if (!t || !t.includes('=')) return;
        const eqIdx = t.indexOf('=');
        const k = t.substring(0, eqIdx).trim();
        const v = t.substring(eqIdx + 1).trim();
        if (k && v && !seen.has(k)) {
          seen.add(k);
          results.push(`${k}=${v}`);
        }
      });

      return results.join('; ') + (results.length > 0 ? ';' : '');
    }

    function executeAutoFormatAndApply(inputEl) {
      const raw = inputEl ? inputEl.value.trim() : '';
      if (!raw) {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('Please paste or enter cookies first');
        }
        return;
      }
      const formatted = cleanAndFormatCookies(raw);
      if (!formatted) {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('Could not find recognizable cookie tokens in text');
        }
        return;
      }
      if (settingsCookiesInput) settingsCookiesInput.value = formatted;
      if (window.AndroidBridge && window.AndroidBridge.saveCustomCookies) {
        window.AndroidBridge.saveCustomCookies(formatted);
      }
      refreshCookiesBadge();
      if (cookieGuideModal) cookieGuideModal.classList.add('hidden');
      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast('Cookies auto-adjusted and applied successfully!');
      }
    }

    if (autoFormatCookiesBtn) {
      autoFormatCookiesBtn.addEventListener('click', () => {
        executeAutoFormatAndApply(settingsCookiesInput);
      });
    }

    if (btnRunAutoAdjust) {
      btnRunAutoAdjust.addEventListener('click', () => {
        executeAutoFormatAndApply(quickAutoAdjustInput);
      });
    }

    if (cookieGuideBtn) {
      cookieGuideBtn.addEventListener('click', () => {
        if (quickAutoAdjustInput && settingsCookiesInput) {
          quickAutoAdjustInput.value = settingsCookiesInput.value;
        }
        if (cookieGuideModal) cookieGuideModal.classList.remove('hidden');
      });
    }

    if (closeCookieGuideBtn) {
      closeCookieGuideBtn.addEventListener('click', () => {
        if (cookieGuideModal) cookieGuideModal.classList.add('hidden');
      });
    }

    if (btnDoneCookieGuide) {
      btnDoneCookieGuide.addEventListener('click', () => {
        if (cookieGuideModal) cookieGuideModal.classList.add('hidden');
      });
    }

    if (cookieGuideModal) {
      cookieGuideModal.addEventListener('click', (e) => {
        if (e.target === cookieGuideModal) {
          cookieGuideModal.classList.add('hidden');
        }
      });
    }

    if (inAppUpdateModal) {
      inAppUpdateModal.addEventListener('click', (e) => {
        if (e.target === inAppUpdateModal) {
          inAppUpdateModal.classList.add('hidden');
        }
      });
    }

    // In-App APK Auto-Updater Controller
    function showUpdateModal(info) {
      const modal = document.getElementById('inAppUpdateModal');
      const badge = document.getElementById('updateVersionBadge');
      const list = document.getElementById('updateChangelogList');
      const progressWrap = document.getElementById('updateDownloadProgressWrap');
      const progressFill = document.getElementById('updateProgressFill');
      const percentText = document.getElementById('updatePercentText');
      const btnNow = document.getElementById('btnUpdateNow');

      if (!modal || !info) return;
      activeUpdateInfo = info;
      if (badge) badge.textContent = `v${info.version} Available`;
      if (list) {
        list.innerHTML = '';
        const notes = Array.isArray(info.releaseNotes) && info.releaseNotes.length > 0
          ? info.releaseNotes
          : [typeof info.releaseNotes === 'string' && info.releaseNotes ? info.releaseNotes : 'Bug fixes and performance improvements'];
        notes.forEach(note => {
          const li = document.createElement('li');
          li.textContent = note;
          list.appendChild(li);
        });
      }
      if (progressWrap) progressWrap.classList.add('hidden');
      if (progressFill) progressFill.style.width = '0%';
      if (percentText) percentText.textContent = '0%';
      if (btnNow) {
        btnNow.disabled = false;
        btnNow.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" class="pill-svg"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg><span>Update Now</span>';
      }
      modal.classList.remove('hidden');
    }

    function hideUpdateModal() {
      const modal = document.getElementById('inAppUpdateModal');
      if (modal) modal.classList.add('hidden');
    }

    const closeUpdateModalBtn = document.getElementById('closeUpdateModalBtn');
    if (closeUpdateModalBtn) {
      closeUpdateModalBtn.addEventListener('click', () => {
        hideUpdateModal();
      });
    }

    if (manualCheckUpdateBtn) {
      manualCheckUpdateBtn.addEventListener('click', () => {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('Checking for MediaFetch updates...');
        }
        if (window.AndroidBridge && window.AndroidBridge.checkForUpdate) {
          window.AndroidBridge.checkForUpdate(true);
        }
      });
    }

    if (btnUpdateNow) {
      btnUpdateNow.addEventListener('click', () => {
        if (!activeUpdateInfo || !activeUpdateInfo.apkUrl) {
          if (window.AndroidBridge && window.AndroidBridge.showToast) {
            window.AndroidBridge.showToast('No update APK download URL available');
          }
          return;
        }
        const progressWrap = document.getElementById('updateDownloadProgressWrap');
        if (progressWrap) progressWrap.classList.remove('hidden');
        btnUpdateNow.disabled = true;
        btnUpdateNow.innerHTML = '<span>Starting Download...</span>';
        if (window.AndroidBridge && window.AndroidBridge.downloadAndInstallUpdateWithVersion) {
          window.AndroidBridge.downloadAndInstallUpdateWithVersion(activeUpdateInfo.apkUrl, activeUpdateInfo.version || '1.0.6');
        } else if (window.AndroidBridge && window.AndroidBridge.downloadAndInstallUpdate) {
          window.AndroidBridge.downloadAndInstallUpdate(activeUpdateInfo.apkUrl);
        }
      });
    }

    if (btnUpdateRemindLater) {
      btnUpdateRemindLater.addEventListener('click', () => {
        if (window.AndroidBridge && window.AndroidBridge.snoozeUpdate) {
          window.AndroidBridge.snoozeUpdate();
        }
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('Reminder snoozed for 24 hours');
        }
        hideUpdateModal();
      });
    }

    if (btnUpdateSkip) {
      btnUpdateSkip.addEventListener('click', () => {
        if (activeUpdateInfo && window.AndroidBridge && window.AndroidBridge.skipUpdate) {
          window.AndroidBridge.skipUpdate(activeUpdateInfo.versionCode || 0);
        }
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(`Skipped version ${activeUpdateInfo ? activeUpdateInfo.version : ''}`);
        }
        hideUpdateModal();
      });
    }

    window.onUpdateAvailable = function (data) {
      if (!data) return;
      const info = data.update || data;
      if (info && info.apkUrl) {
        showUpdateModal(info);
      }
    };

    window.onUpdateAvailableBase64 = function (b64) {
      try {
        const jsonStr = decodeURIComponent(escape(atob(b64)));
        const data = JSON.parse(jsonStr);
        window.onUpdateAvailable(data);
      } catch (err) {
        console.error('Failed to parse update available Base64', err);
      }
    };

    window.onUpdateCheckResult = function (res) {
      if (!res) {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('You are on the latest MediaFetch build!');
        }
        return;
      }
      if (res.error) {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast(res.error);
        }
        return;
      }
      const info = res.update || (res.hasUpdate ? res.update : null);
      if (info && info.apkUrl) {
        showUpdateModal(info);
      } else {
        if (window.AndroidBridge && window.AndroidBridge.showToast) {
          window.AndroidBridge.showToast('You are on the latest MediaFetch build!');
        }
      }
    };

    window.onUpdateCheckResultBase64 = function (b64) {
      try {
        const jsonStr = decodeURIComponent(escape(atob(b64)));
        const data = JSON.parse(jsonStr);
        window.onUpdateCheckResult(data);
      } catch (err) {
        console.error('Failed to parse update check result Base64', err);
      }
    };

    window.onUpdateDownloadProgress = function (percent, savedPath) {
      const p = Math.max(0, Math.min(100, Math.round(percent || 0)));
      const progressWrap = document.getElementById('updateDownloadProgressWrap');
      const percentText = document.getElementById('updatePercentText');
      const progressFill = document.getElementById('updateProgressFill');
      const btnNow = document.getElementById('btnUpdateNow');
      const savedBox = document.getElementById('updateSavedLocationBox');
      const savedPathText = document.getElementById('updateSavedPathText');

      if (progressWrap) progressWrap.classList.remove('hidden');
      if (percentText) percentText.textContent = `${p}%`;
      if (progressFill) progressFill.style.width = `${p}%`;

      if (savedPath && savedPathText) {
        savedPathText.textContent = `Internal Storage > ${savedPath.replace(/\//g, ' > ')}`;
      }

      if (btnNow) {
        btnNow.disabled = true;
        if (p >= 100) {
          btnNow.innerHTML = '<span>Launching Installer...</span>';
          if (savedBox) savedBox.classList.remove('hidden');
        } else {
          btnNow.innerHTML = `<span>Downloading... ${p}%</span>`;
        }
      }
    };

    window.onUpdateDownloadError = function (errMsg) {
      const btnNow = document.getElementById('btnUpdateNow');
      if (btnNow) {
        btnNow.disabled = false;
        btnNow.innerHTML = '<span>Retry Update</span>';
      }
      if (window.AndroidBridge && window.AndroidBridge.showToast) {
        window.AndroidBridge.showToast('Update download failed: ' + errMsg);
      }
    };

    refreshSettingsUI();
  }

  window.handleBackPress = function () {
    const customColorPickerModal = document.getElementById('customColorPickerModal');
    if (customColorPickerModal && !customColorPickerModal.classList.contains('hidden')) {
      if (typeof cancelColorPickerSelection === 'function') {
        cancelColorPickerSelection();
      } else {
        customColorPickerModal.classList.add('hidden');
      }
      return true;
    }
    if (cookieGuideModal && !cookieGuideModal.classList.contains('hidden')) {
      cookieGuideModal.classList.add('hidden');
      return true;
    }
    if (inAppUpdateModal && !inAppUpdateModal.classList.contains('hidden')) {
      inAppUpdateModal.classList.add('hidden');
      return true;
    }
    if (searchFormatModal && !searchFormatModal.classList.contains('hidden')) {
      closeSearchFormatModal();
      return true;
    }
    if (downloadsDrawer.classList.contains('open')) {
      downloadsDrawer.classList.remove('open');
      return true;
    }
    if (currentAppMode === 'settings' || currentAppMode === 'search' || currentAppMode === 'history') {
      toggleAppMode('fetch');
      return true;
    }
    return false;
  };

  initTheme();
    // Setup Floating Bottom Nav Click Handlers
  try {
    document.querySelectorAll('#floatingBottomNav .floating-nav-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const mode = btn.getAttribute('data-mode');
        if (mode) toggleAppMode(mode);
      });
    });
  } catch (_) {}
  initSettingsController();
})();
