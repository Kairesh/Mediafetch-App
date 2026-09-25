let currentMedia = null;
let currentTab = 'video';
let sharedUrl = '';
let pendingDownloadQuality = null;

document.addEventListener('DOMContentLoaded', () => {
    initShareSheet();
});

function formatBytes(bytes) {
    if (!bytes || isNaN(bytes) || bytes <= 0) return '18.0 MB';
    const mb = bytes / (1024 * 1024);
    if (mb >= 1024) return (mb / 1024).toFixed(2) + ' GB';
    if (mb >= 0.1) return mb.toFixed(1) + ' MB';
    return (bytes / 1024).toFixed(0) + ' KB';
}

function initShareSheet() {
    document.getElementById('backdrop').addEventListener('click', dismiss);
    document.getElementById('closeBtn').addEventListener('click', dismiss);

    document.querySelectorAll('.format-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.format-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentTab = btn.dataset.tab;
            if (window.androidBridge) window.androidBridge.vibrate();
            renderQualities();
        });
    });

    document.getElementById('openStudioBtn').addEventListener('click', () => {
        if (window.androidBridge) {
            window.androidBridge.openInStudio(sharedUrl);
        }
    });

    document.getElementById('overwriteBtn').addEventListener('click', () => {
        document.getElementById('duplicateModal').style.display = 'none';
        if (pendingDownloadQuality) {
            executeDownload(pendingDownloadQuality);
            pendingDownloadQuality = null;
        }
    });

    document.getElementById('cancelDupBtn').addEventListener('click', () => {
        document.getElementById('duplicateModal').style.display = 'none';
        pendingDownloadQuality = null;
    });

    if (window.androidBridge) {
        try {
            const data = JSON.parse(window.androidBridge.getSharedData());
            sharedUrl = data.url;
            if (data.safeBottom) {
                document.documentElement.style.setProperty('--safe-bottom', `${data.safeBottom}px`);
            }
            if (data.accentColor) {
                document.documentElement.style.setProperty('--apple-blue', data.accentColor);
                document.documentElement.style.setProperty('--accent-glow', `${data.accentColor}33`);
            }
            if (data.surfaceColor) {
                document.documentElement.style.setProperty('--card-surface', data.surfaceColor);
            }
            if (data.bgColor) {
                document.documentElement.style.setProperty('--bg-amoled', data.bgColor);
            }
            if (data.isDark === false) {
                document.documentElement.setAttribute('data-theme', 'light');
                document.documentElement.style.setProperty('--bg-amoled', '#F2F4F7');
                document.documentElement.style.setProperty('--card-surface', '#FFFFFF');
                document.documentElement.style.setProperty('--card-border', 'rgba(0, 0, 0, 0.08)');
                document.documentElement.style.setProperty('--text-primary', '#1C1C1E');
                document.documentElement.style.setProperty('--text-secondary', '#6C6C70');
            }
            if (sharedUrl) {
                window.androidBridge.resolveMedia(sharedUrl);
            }
        } catch (e) {
            console.error(e);
        }
    }
}

window.onMediaResolved = function(mediaInfo) {
    currentMedia = mediaInfo;
    document.getElementById('loadingBox').style.display = 'none';
    document.getElementById('contentBox').style.display = 'flex';

    const thumb = mediaInfo.thumbnail || mediaInfo.thumbnailUrl || 'https://images.unsplash.com/photo-1611162617474-5b21e879e113?w=800';
    document.getElementById('mediaThumb').src = thumb;
    document.getElementById('mediaTitle').innerText = mediaInfo.title || 'Shared Media';
    document.getElementById('mediaAuthor').innerText = mediaInfo.author || 'Author';
    if (mediaInfo.mediaType === 'IMAGE') {
        document.getElementById('mediaDuration').innerText = 'PHOTO';
    } else {
        document.getElementById('mediaDuration').innerText = mediaInfo.formattedDuration || mediaInfo.durationFormatted || '00:45';
    }
    document.getElementById('platformTag').innerText = mediaInfo.platform || 'Media';

    const isGif = (mediaInfo.platform === 'Giphy' || mediaInfo.platform === 'Tenor') ||
                  (mediaInfo.qualities && mediaInfo.qualities.some(q => q.ext === 'gif' || q.id === 'img_gif')) ||
                  (mediaInfo.url && mediaInfo.url.toLowerCase().includes('.gif')) ||
                  (mediaInfo.thumbnail && mediaInfo.thumbnail.toLowerCase().includes('.gif')) ||
                  (mediaInfo.imageUrls && mediaInfo.imageUrls.some(u => u.toLowerCase().includes('.gif')));

    if (isGif) {
        const durBadge = document.getElementById('mediaDuration');
        if (durBadge) {
            durBadge.innerText = 'GIF';
            durBadge.style.background = 'rgba(191,90,242,0.85)';
            durBadge.style.color = '#fff';
        }
    }

    const hasVideo = (mediaInfo.qualities && mediaInfo.qualities.some(q => !q.isAudioOnly && !q.isImage)) ||
                     (mediaInfo.carouselSlides && mediaInfo.carouselSlides.some(s => s.mediaType === 'video'));
    const hasAudio = (mediaInfo.qualities && mediaInfo.qualities.some(q => q.isAudioOnly));
    const hasPhotos = mediaInfo.mediaType === 'IMAGE' || 
                      isGif ||
                      (mediaInfo.qualities && mediaInfo.qualities.some(q => q.isImage)) ||
                      (mediaInfo.carouselSlides && mediaInfo.carouselSlides.some(s => s.mediaType === 'image')) ||
                      (mediaInfo.imageUrls && mediaInfo.imageUrls.length > 0) ||
                      Boolean(mediaInfo.thumbnail && mediaInfo.thumbnail.startsWith('http'));

    const videoTabBtn = document.getElementById('videoTabBtn');
    const audioTabBtn = document.getElementById('audioTabBtn');
    const photoTabBtn = document.getElementById('photoTabBtn');
    const formatNav = document.querySelector('.format-nav');

    // Configure tab visibility based on available formats
    let visibleTabCount = 0;
    if (hasVideo) {
        videoTabBtn.style.display = 'block';
        visibleTabCount++;
    } else {
        videoTabBtn.style.display = 'none';
    }

    if (hasAudio && hasVideo) {
        audioTabBtn.style.display = 'block';
        visibleTabCount++;
    } else if (hasAudio && !hasVideo && !hasPhotos) {
        audioTabBtn.style.display = 'block';
        visibleTabCount++;
    } else {
        audioTabBtn.style.display = 'none';
    }

    if (hasPhotos) {
        photoTabBtn.style.display = 'block';
        visibleTabCount++;
    } else {
        photoTabBtn.style.display = 'none';
    }

    formatNav.className = 'format-nav cols-' + Math.max(1, visibleTabCount);

    document.querySelectorAll('.format-btn').forEach(b => b.classList.remove('active'));

    // Dynamic initial active tab selection
    if (hasVideo && hasPhotos) {
        // Both video and photos available
        currentTab = 'video';
        videoTabBtn.classList.add('active');
    } else if (hasPhotos && !hasVideo) {
        // Only photos available
        currentTab = 'photos';
        photoTabBtn.classList.add('active');
    } else if (hasVideo) {
        currentTab = 'video';
        videoTabBtn.classList.add('active');
    } else if (hasAudio) {
        currentTab = 'audio';
        audioTabBtn.classList.add('active');
    } else {
        currentTab = 'photos';
        photoTabBtn.style.display = 'block';
        photoTabBtn.classList.add('active');
    }

    // Render Carousel Multi-slide Track if available
    renderCarouselSlides(mediaInfo);

    renderQualities();
};

function renderCarouselSlides(mediaInfo) {
    const carouselSection = document.getElementById('carouselSection');
    const carouselSlidesTrack = document.getElementById('carouselSlidesTrack');
    const carouselCountBadge = document.getElementById('carouselCountBadge');
    const downloadAllSlidesBtn = document.getElementById('downloadAllSlidesBtn');

    if (!carouselSection || !carouselSlidesTrack) return;

    const slides = (mediaInfo.carouselSlides && mediaInfo.carouselSlides.length > 0)
        ? mediaInfo.carouselSlides
        : (mediaInfo.imageUrls && mediaInfo.imageUrls.length > 1
            ? mediaInfo.imageUrls.map((u, i) => ({ slideIndex: i + 1, url: u, thumbnail: u, mediaType: 'image' }))
            : []);

    if (slides.length <= 1) {
        carouselSection.style.display = 'none';
        return;
    }

    carouselSection.style.display = 'block';
    carouselSlidesTrack.innerHTML = '';
    if (carouselCountBadge) carouselCountBadge.innerText = `${slides.length} slides`;

    slides.forEach((slide) => {
        const item = document.createElement('div');
        item.className = 'carousel-slide-item';
        const isVid = slide.mediaType === 'video';
        item.innerHTML = `
            <div class="slide-img-box">
                <img src="${slide.thumbnail || slide.url}" alt="Slide ${slide.slideIndex}" onerror="this.src='https://images.unsplash.com/photo-1611162617474-5b21e879e113?w=800';" />
                <span class="slide-badge">#${slide.slideIndex} ${isVid ? '🎬' : '📸'}</span>
            </div>
            <button type="button" class="slide-action-btn">⬇ Get</button>
        `;

        item.querySelector('.slide-action-btn').addEventListener('click', (e) => {
            e.stopPropagation();
            if (window.androidBridge) {
                if (window.androidBridge.downloadSlide) {
                    window.androidBridge.downloadSlide(JSON.stringify(slide), isVid ? 'video' : 'photo');
                } else {
                    const qObj = {
                        id: 'slide_' + slide.slideIndex,
                        label: `Slide ${slide.slideIndex} ${isVid ? 'Video' : 'Photo'}`,
                        resolution: isVid ? 'HD Video' : 'Original Photo',
                        format: isVid ? 'MP4' : 'JPG',
                        ext: isVid ? 'mp4' : 'jpg',
                        estimatedSizeBytes: isVid ? 15 * 1024 * 1024 : 3 * 1024 * 1024,
                        isImage: !isVid,
                        directDownloadUrl: slide.url
                    };
                    window.androidBridge.startDownload(
                        `Slide ${slide.slideIndex}`,
                        mediaInfo.author || 'Instagram',
                        slide.thumbnail || slide.url,
                        slide.url,
                        JSON.stringify(qObj),
                        qObj.estimatedSizeBytes
                    );
                }
            }
        });

        carouselSlidesTrack.appendChild(item);
    });

    if (downloadAllSlidesBtn) {
        downloadAllSlidesBtn.onclick = () => {
            if (window.androidBridge && window.androidBridge.downloadAllSlides) {
                window.androidBridge.downloadAllSlides(JSON.stringify(slides), 'best');
            } else {
                slides.forEach((s, idx) => {
                    setTimeout(() => {
                        const isVid = s.mediaType === 'video';
                        const qObj = {
                            id: 'slide_' + s.slideIndex,
                            label: `Slide ${s.slideIndex} ${isVid ? 'Video' : 'Photo'}`,
                            resolution: isVid ? 'HD Video' : 'Original Photo',
                            format: isVid ? 'MP4' : 'JPG',
                            ext: isVid ? 'mp4' : 'jpg',
                            estimatedSizeBytes: isVid ? 15 * 1024 * 1024 : 3 * 1024 * 1024,
                            isImage: !isVid,
                            directDownloadUrl: s.url
                        };
                        window.androidBridge.startDownload(
                            `Slide ${s.slideIndex}`,
                            mediaInfo.author || 'Instagram',
                            s.thumbnail || s.url,
                            s.url,
                            JSON.stringify(qObj),
                            qObj.estimatedSizeBytes
                        );
                    }, idx * 300);
                });
            }
        };
    }
}

function renderQualities() {
    const container = document.getElementById('qualitiesContainer');
    container.innerHTML = '';

    if (!currentMedia || !currentMedia.qualities) return;

    let filtered = [];
    if (currentTab === 'video') {
        filtered = currentMedia.qualities.filter(q => !q.isAudioOnly && !q.isImage);
    } else if (currentTab === 'audio') {
        filtered = currentMedia.qualities.filter(q => q.isAudioOnly);
    } else if (currentTab === 'photos') {
        filtered = currentMedia.qualities.filter(q => q.isImage);
        const isGifMedia = (currentMedia && (
            currentMedia.platform === 'Giphy' ||
            currentMedia.platform === 'Tenor' ||
            (currentMedia.url && currentMedia.url.toLowerCase().includes('.gif')) ||
            (currentMedia.thumbnail && currentMedia.thumbnail.toLowerCase().includes('.gif')) ||
            (currentMedia.imageUrls && currentMedia.imageUrls.some(u => u.toLowerCase().includes('.gif')))
        )) || (currentMedia.qualities || []).some(q => q.ext === 'gif' || q.id === 'img_gif');

        if (isGifMedia && !filtered.some(q => q.ext === 'gif' || q.id === 'img_gif')) {
            const rawImg = (currentMedia.imageUrls && currentMedia.imageUrls[0]) || currentMedia.thumbnail || '';
            filtered.unshift({
                id: 'img_gif',
                label: 'Animated GIF (Original Motion)',
                resolution: 'GIF Animation',
                format: 'Animation • GIF',
                ext: 'gif',
                estimatedSizeBytes: 3 * 1024 * 1024,
                formattedSize: '3.0 MB',
                isImage: true,
                directDownloadUrl: rawImg
            });
        }

        if (filtered.length === 0 && (currentMedia.thumbnail || (currentMedia.imageUrls && currentMedia.imageUrls.length > 0))) {
            const rawImg = (currentMedia.imageUrls && currentMedia.imageUrls[0]) || currentMedia.thumbnail;
            const synthList = [];
            if (isGifMedia) {
                synthList.push({
                    id: 'img_gif',
                    label: 'Animated GIF (Original Motion)',
                    resolution: 'GIF Animation',
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
                    label: isGifMedia ? 'Original GIF' : 'Original Photo (Ultra HD)',
                    resolution: isGifMedia ? 'GIF Animation' : 'Original Resolution • 100% Quality',
                    format: isGifMedia ? 'Animation • GIF' : 'Image • JPG',
                    ext: isGifMedia ? 'gif' : 'jpg',
                    estimatedSizeBytes: 4 * 1024 * 1024,
                    formattedSize: '4.0 MB',
                    isImage: true,
                    directDownloadUrl: rawImg
                },
                {
                    id: 'img_1080p',
                    label: 'Full HD Photo (1080p)',
                    resolution: '1920×1080 • Web Crisp',
                    format: 'Image • JPG',
                    ext: 'jpg',
                    estimatedSizeBytes: Math.round(1.5 * 1024 * 1024),
                    formattedSize: '1.5 MB',
                    isImage: true,
                    directDownloadUrl: rawImg
                }
            );
            filtered = synthList;
        }
    }

    if (filtered.length === 0) {
        container.innerHTML = '<div style="text-align:center; padding:20px; color:#8E8E93; font-size:0.85rem;">No formats available for this category</div>';
        return;
    }

    filtered.forEach(q => {
        const card = document.createElement('div');
        card.className = 'quality-card';

        const isGifCard = q.ext === 'gif' || q.id.includes('gif');
        const isRec = !isGifCard && (q.label.includes('1080p') || q.label.includes('320k') || q.label.includes('Original'));
        const badgeLabel = q.resolution || q.ext.toUpperCase();
        const sizeString = (q.formattedSize && q.formattedSize !== 'undefined') ? q.formattedSize : formatBytes(q.estimatedSizeBytes);

        card.innerHTML = `
            <div class="quality-info">
                <div class="quality-badge-row">
                    <span class="resolution-pill" style="${isGifCard ? 'background:rgba(191,90,242,0.18);color:var(--apple-purple, #bf5af2);' : ''}">${isGifCard ? 'GIF' : badgeLabel}</span>
                    <span class="quality-title">${q.label}</span>
                    ${isGifCard ? '<span class="recommended-tag" style="background:rgba(191,90,242,0.18);color:var(--apple-purple, #bf5af2);border-color:rgba(191,90,242,0.3);">ANIMATED GIF</span>' : (isRec ? '<span class="recommended-tag">Recommended</span>' : '')}
                </div>
                <span class="quality-specs">${isGifCard ? 'Infinite Loop • High Quality GIF' : (q.codec || q.ext.toUpperCase()) + ' • Fast Download'}</span>
            </div>
            <div class="size-download-group">
                <span class="file-size">${sizeString}</span>
                <button class="btn-instant-dl" aria-label="Download" style="${isGifCard ? 'background:var(--apple-purple, #bf5af2);' : ''}">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                </button>
            </div>
        `;

        const btn = card.querySelector('.btn-instant-dl');
        btn.addEventListener('click', () => {
            // Check if file already exists
            if (window.androidBridge && window.androidBridge.checkFileExists) {
                const exists = window.androidBridge.checkFileExists(currentMedia.title, q.ext || 'mp4');
                if (exists) {
                    pendingDownloadQuality = q;
                    document.getElementById('duplicateDesc').innerText = `You already have "${currentMedia.title}" (${q.label}) downloaded. Do you want to download again?`;
                    document.getElementById('duplicateModal').style.display = 'flex';
                    return;
                }
            }

            btn.classList.add('success');
            btn.innerHTML = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>`;
            executeDownload(q);
        });

        container.appendChild(card);
    });
}

function executeDownload(q) {
    if (window.androidBridge) {
        const bytes = q.estimatedSizeBytes || (1024 * 1024 * 25);
        const thumb = currentMedia.thumbnail || currentMedia.thumbnailUrl || '';
        window.androidBridge.startDownload(
            currentMedia.title,
            currentMedia.author,
            thumb,
            currentMedia.sourceUrl || sharedUrl,
            JSON.stringify(q),
            bytes
        );
    }
}

function dismiss() {
    if (window.androidBridge) {
        window.androidBridge.dismiss();
    }
}
