import Foundation

func letterAccent(for worktree: String) -> LetterAccent {
    let hash = worktree.utf8.reduce(5381) { ($0 << 5) &+ $0 &+ Int($1) }
    let index = abs(hash) % LetterAccent.allCases.count
    return LetterAccent.allCases[index]
}

func letterCharacter(for displayName: String) -> Character {
    displayName.first.map { Character(String($0).uppercased()) } ?? "?"
}
