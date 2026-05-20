import Foundation

func letterAccent(for worktree: String) -> LetterAccent {
    let index = abs(worktree.hashValue) % LetterAccent.allCases.count
    return LetterAccent.allCases[index]
}

func letterCharacter(for displayName: String) -> Character {
    displayName.first.map { Character(String($0).uppercased()) } ?? "?"
}
