export const INTERVIEW_CAMERA_CONSTRAINTS: MediaStreamConstraints = {
  video: {
    // The camera is a live presence preview; only audio is submitted. Keep the
    // preview deliberately light so long interviews do not saturate laptops.
    width: { ideal: 320, max: 640 },
    height: { ideal: 240, max: 480 },
    frameRate: { ideal: 8, max: 12 },
    facingMode: { ideal: "user" },
  },
};
