/**
 * GRSAI（gpt-image-2.6）换装提示词。
 * 与后端 backend/internal/logic/tryon/prompt.go 的 gptSafetyEnPrompt 同源，
 * 这里单独放一份，方便不重启后端也能调。
 */

export const GRSAI_TRYON_PROMPT = `Task: Professional commercial fashion virtual try-on photograph.

Strict Requirements:
1. Model Identity: Maintain the adult female model from Image 1 - faithfully preserve her facial features, ethnic identity, eye shape, smile, skin tone, and hairstyle. Keep her identity consistent.
2. Wardrobe: Dress her in the exact clothing and accessories shown in Image 2. Faithfully reproduce the colors, fabric textures, patterns, and silhouette.
3. Safety & Fit: Professional high-end commercial lookbook standard. Fully clothed, modest neckline, standard tailored fit, upright standing posture, non-revealing.
4. Composition: Clean bright studio lighting, neutral minimal background, photorealistic 8k, high detail fashion editorial photograph.

Output: ONLY the final dressed-up photograph.`

export const GRSAI_ANIME_TRYON_PROMPT = `Task: Anime-style virtual try-on illustration.

Strict Requirements:
1. Character Identity: Keep the same face, hairstyle and body proportions as the person in Image 1, converted into a clean anime / illustration rendering.
2. Wardrobe: Dress the character in the exact outfit from Image 2 - same colors, patterns, layering and silhouette.
3. Style: High-quality anime illustration, crisp line art, soft cel shading, clean neutral background.
4. Safety: Fully clothed, tasteful, non-revealing.

Output: ONLY the final full-body anime illustration.`

/**
 * 真人照片 + 二次元服装：必须把人物整体转成二次元插画，再穿上动漫服装，
 * 否则会出现「真人身体 + 动漫衣服」的割裂感（实测很容易翻车）。
 */
export const GRSAI_ANIME_FROM_PHOTO_PROMPT = `Task: Anime-style virtual try-on illustration, converted from a real photo.

Inputs:
- Image 1: a real photo of a person (identity reference, photorealistic).
- Image 2: an anime / 2D style outfit (wardrobe reference).

Strict Requirements:
1. Style Conversion (most important): Repaint the person from Image 1 as a clean 2D anime character. The final image must be a pure 2D illustration - NOT a photorealistic photo, NOT a 3D render, NOT a photo body with an anime outfit pasted on. No photoreal skin pores, no camera blur, no real-photo texture.
2. Identity Preservation: Even after the anime conversion, keep the same face structure, eye shape and eye colour, hairstyle and hair colour, skin tone, and body proportions as Image 1. She must still be recognisable as the same person, just drawn in anime style.
3. Wardrobe Fidelity: Dress her in the exact outfit from Image 2 - identical colours, patterns, prints, layering, trims, accessories and silhouette (skirt length, sleeve length, neckline). Do not redesign, simplify or "photo-realify" the outfit; keep its original anime illustration look.
4. Style Unification: The character and the outfit must live in ONE consistent art style - same line weight, same cel-shading and gradient treatment, same colour palette and lighting. The outfit must not look sharper or flatter than the character.
5. Composition: Full body or three-quarter body, upright natural pose, centred, clean simple background (plain light wall or soft studio backdrop), even soft lighting, no clutter, no watermark, no text.
6. Safety: Fully clothed, modest neckline, tasteful and non-revealing.

Output: ONLY the final anime illustration, single frame.`

/**
 * 真人照片 + 二次元服装 + 姿势骨架：既要转二次元，又要严格复刻 Image 3 的姿态。
 */
export const GRSAI_ANIME_POSE_PROMPT = `Task: Anime-style virtual try-on illustration from a real photo, with a pose reference.

Inputs:
- Image 1: a real photo of a person (identity reference, photorealistic).
- Image 2: an anime / 2D style outfit (wardrobe reference).
- Image 3: the pose reference (body posture / skeleton only).

Strict Requirements:
1. Style Conversion (most important): Repaint the person from Image 1 as a clean 2D anime character. The final image must be a pure 2D illustration - NOT a photorealistic photo, NOT a 3D render, NOT a photo body wearing an anime outfit.
2. Identity Preservation: keep the same face structure, eye shape and colour, hairstyle and hair colour, skin tone and body proportions as Image 1, so she stays recognisable in anime form.
3. Wardrobe Fidelity: dress her in the exact outfit from Image 2 - same colours, patterns, layering, trims and accessories; keep its original anime look, do not redesign or simplify it.
4. Pose Fidelity: strictly replicate the body posture from Image 3 - limb placement, torso twist, shoulder and hip tilt, hand and finger position, weight-bearing leg, camera angle and framing. Take ONLY the geometry from Image 3, never its identity, face, skin or clothing.
5. Style Unification: character and outfit share one art style - same line weight, cel-shading, palette and lighting.
6. Composition & Safety: clean simple background, even soft lighting, no text or watermark; fully clothed, modest, tasteful, non-revealing.

Output: ONLY the final anime illustration, single frame, matching the pose and framing of Image 3.`

/**
 * 统一入口：按「服装是否二次元」+「是否选姿势」选提示词。
 * 服装是二次元时一律走风格转换版，避免真人身体配动漫衣服。
 */
export function buildTryonPrompt(options: { anime?: boolean, pose?: boolean } = {}): string {
  const { anime = false, pose = false } = options
  if (anime && pose)
    return GRSAI_ANIME_POSE_PROMPT
  if (anime)
    return GRSAI_ANIME_FROM_PHOTO_PROMPT
  if (pose)
    return GRSAI_POSE_TRYON_PROMPT
  return GRSAI_TRYON_PROMPT
}

/**
 * 三图版本：人像 + 服装 + 姿势参考图。
 * 姿势图只提供骨架/姿态/构图信息，人物的面容与服装仍来自 Image 1 / Image 2。
 */
export const GRSAI_POSE_TRYON_PROMPT = `Task: Professional commercial fashion virtual try-on photograph with a pose reference.

Inputs:
- Image 1: the model (identity source).
- Image 2: the garment (wardrobe source).
- Image 3: the pose reference (skeleton / body posture reference only).

Strict Requirements:
1. Model Identity: Maintain the adult female model from Image 1 - faithfully preserve her facial features, ethnic identity, eye shape, smile, skin tone, and hairstyle. Keep her identity consistent.
2. Wardrobe: Dress her in the exact clothing and accessories shown in Image 2. Faithfully reproduce the colors, fabric textures, patterns, and silhouette.
3. Pose Fidelity: Strictly replicate the body posture from Image 3 - the same limb placement, torso twist, shoulder and hip tilt, hand gesture and finger position, weight-bearing leg, camera angle, and framing. The pose must match Image 3 exactly, not approximately.
4. Pose Adaptation: Adapt Image 3's pose so it looks natural for the adult model of Image 1, keeping the same camera height and lens perspective. Never copy the identity, face, skin, or clothing of Image 3 - only its geometry.
5. Safety & Fit: Professional high-end commercial lookbook standard. Fully clothed, modest neckline, standard tailored fit, non-revealing, no nudity, no see-through fabric, no suggestive exposure.
6. Composition: Clean bright studio lighting, neutral minimal background, photorealistic 8k, high detail fashion editorial photograph.

Output: ONLY the final dressed-up photograph, single frame, matching the pose and framing of Image 3.`
